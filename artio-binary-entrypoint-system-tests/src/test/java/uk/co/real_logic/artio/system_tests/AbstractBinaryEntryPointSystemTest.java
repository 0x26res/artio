/*
 * Copyright 2021 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.system_tests;

import io.aeron.archive.ArchivingMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.junit.After;
import org.mockito.Mockito;
import uk.co.real_logic.artio.MonitoringAgentFactory;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointContext;
import uk.co.real_logic.artio.binary_entrypoint.BinaryEntrypointConnection;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.ILink3RetransmitHandler;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.fixp.FixPCancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.SessionReplyStatus;

import java.io.IOException;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.library.LibraryConfiguration.NO_FIXP_MAX_RETRANSMISSION_RANGE;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.ACCEPTOR_LOGS;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.TEST_REPLY_TIMEOUT_IN_MS;

public class AbstractBinaryEntryPointSystemTest
{
    public static final long TEST_TIMEOUT_IN_MS = 20_000L;

    static final int AWAIT_TIMEOUT_IN_MS = 10_000;
    static final int TIMEOUT_EPSILON_IN_MS = 10;
    static final int TEST_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS = 200;

    final EpochNanoClock nanoClock = new OffsetEpochNanoClock();
    final int port = unusedPort();

    ArchivingMediaDriver mediaDriver;
    TestSystem testSystem;
    FixEngine engine;
    FixLibrary library;

    final ErrorHandler errorHandler = mock(ErrorHandler.class);
    final ILink3RetransmitHandler retransmitHandler = mock(ILink3RetransmitHandler.class);
    final FakeFixPConnectionExistsHandler connectionExistsHandler = new FakeFixPConnectionExistsHandler();
    final FakeBinaryEntrypointConnectionHandler connectionHandler = new FakeBinaryEntrypointConnectionHandler();
    final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler = new FakeFixPConnectionAcquiredHandler(
        connectionHandler);
    final FakeFixPAuthenticationStrategy fixPAuthenticationStrategy = new FakeFixPAuthenticationStrategy();

    BinaryEntrypointConnection connection;

    boolean printErrors = true;

    void setupArtio()
    {
        setup();
        setupJustArtio(true);
    }

    void setup()
    {
        mediaDriver = launchMediaDriver();
        newTestSystem();
    }

    void newTestSystem()
    {
        testSystem = new TestSystem().awaitTimeoutInMs(AWAIT_TIMEOUT_IN_MS);
    }

    void setupArtio(
        final int logonTimeoutInMs,
        final int fixPAcceptedSessionMaxRetransmissionRange)
    {
        setup();
        setupJustArtio(true, logonTimeoutInMs, fixPAcceptedSessionMaxRetransmissionRange, null);
    }

    void setupJustArtio(final boolean deleteLogFileDirOnStart)
    {
        setupJustArtio(
            deleteLogFileDirOnStart,
            DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS,
            NO_FIXP_MAX_RETRANSMISSION_RANGE,
            null);
    }

    void setupJustArtio(
        final boolean deleteLogFileDirOnStart,
        final int shortLogonTimeoutInMs,
        final int fixPAcceptedSessionMaxRetransmissionRange,
        final FixPCancelOnDisconnectTimeoutHandler cancelOnDisconnectTimeoutHandler)
    {
        final EngineConfiguration engineConfig = new EngineConfiguration()
            .logFileDir(ACCEPTOR_LOGS)
            .scheduler(new LowResourceEngineScheduler())
            .libraryAeronChannel(IPC_CHANNEL)
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS)
            .noLogonDisconnectTimeoutInMs(shortLogonTimeoutInMs)
            .fixPAuthenticationStrategy(fixPAuthenticationStrategy)
            .fixPRetransmitHandler(retransmitHandler)
            .fixPCancelOnDisconnectTimeoutHandler(cancelOnDisconnectTimeoutHandler)
            .acceptFixPProtocol(FixPProtocolType.BINARY_ENTRYPOINT)
            .bindTo("localhost", port)
            .deleteLogFileDirOnStart(deleteLogFileDirOnStart)
            .epochNanoClock(nanoClock);

        if (!printErrors)
        {
            engineConfig
                .errorHandlerFactory(errorBuffer -> errorHandler)
                .monitoringAgentFactory(MonitoringAgentFactory.none());
        }

        engine = FixEngine.launch(engineConfig);

        library = launchLibrary(
            shortLogonTimeoutInMs,
            fixPAcceptedSessionMaxRetransmissionRange,
            connectionExistsHandler,
            connectionAcquiredHandler);
    }

    FixLibrary launchLibrary(
        final int shortLogonTimeoutInMs,
        final int fixPAcceptedSessionMaxRetransmissionRange,
        final FakeFixPConnectionExistsHandler connectionExistsHandler,
        final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler)
    {
        final LibraryConfiguration libraryConfig = new LibraryConfiguration()
            .libraryAeronChannels(singletonList(IPC_CHANNEL))
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS)
            .fixPConnectionExistsHandler(connectionExistsHandler)
            .fixPConnectionAcquiredHandler(connectionAcquiredHandler)
            .noEstablishFixPTimeoutInMs(shortLogonTimeoutInMs)
            .fixPAcceptedSessionMaxRetransmissionRange(fixPAcceptedSessionMaxRetransmissionRange)
            .epochNanoClock(nanoClock);

        if (!printErrors)
        {
            libraryConfig
                .errorHandlerFactory(errorBuffer -> errorHandler)
                .monitoringAgentFactory(MonitoringAgentFactory.none());
        }

        return testSystem.connect(libraryConfig);
    }

    @After
    public void close()
    {
        closeArtio();
        cleanupMediaDriver(mediaDriver);

        if (printErrors)
        {
            verifyNoInteractions(errorHandler);
        }

        Mockito.framework().clearInlineMocks();
    }

    void closeArtio()
    {
        testSystem.awaitBlocking(() -> CloseHelper.close(engine));
        testSystem.close(library);
    }



    BinaryEntryPointClient establishNewConnection() throws IOException
    {
        final BinaryEntryPointClient client = newClient();
        establishNewConnection(client);
        return client;
    }

    void establishNewConnection(final BinaryEntryPointClient client)
    {
        establishNewConnection(client, connectionExistsHandler, connectionAcquiredHandler);
    }

    void establishNewConnection(
        final BinaryEntryPointClient client,
        final FakeFixPConnectionExistsHandler connectionExistsHandler,
        final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler)
    {
        client.writeNegotiate();

        libraryAcquiresConnection(client, connectionExistsHandler, connectionAcquiredHandler);

        client.readNegotiateResponse();

        client.writeEstablish();
        client.readFirstEstablishAck();

        assertConnectionMatches(client, connectionAcquiredHandler);
    }

    void resetHandlers()
    {
        connectionHandler.replyToOrder(true);
        connectionHandler.reset();
        connectionExistsHandler.reset();
        connectionAcquiredHandler.reset();
        connection = null;
    }

    BinaryEntryPointClient newClient() throws IOException
    {
        return new BinaryEntryPointClient(port, testSystem);
    }

    void assertConnectionMatches(final BinaryEntryPointClient client)
    {
        assertConnectionMatches(client, connectionAcquiredHandler);
    }

    void assertConnectionMatches(
        final BinaryEntryPointClient client, final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler)
    {
        acquireConnection(connectionAcquiredHandler);
        assertEquals(client.sessionId(), connection.sessionId());
        assertEquals(client.sessionVerID(), connection.sessionVerId());
        assertEquals(FixPConnection.State.ESTABLISHED, connection.state());
    }

    void acquireConnection(final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler)
    {
        connection = (BinaryEntrypointConnection)connectionAcquiredHandler.connection();
    }

    void libraryAcquiresConnection(
        final BinaryEntryPointClient client,
        final FakeFixPConnectionExistsHandler connectionExistsHandler,
        final FakeFixPConnectionAcquiredHandler connectionAcquiredHandler)
    {
        testSystem.await("connection doesn't exist", connectionExistsHandler::invoked);

        final BinaryEntryPointContext context = (BinaryEntryPointContext)fixPAuthenticationStrategy.lastSessionId();
        assertNotNull(context);
        assertEquals(client.sessionId(), context.sessionID());
        assertEquals(client.sessionVerID(), context.sessionVerID());
//        assertEquals(FIRM_ID, context.enteringFirm());

        assertEquals(client.sessionId(), connectionExistsHandler.lastSurrogateSessionId());
        final BinaryEntryPointContext id =
            (BinaryEntryPointContext)connectionExistsHandler.lastIdentification();
        assertEquals(client.sessionId(), id.sessionID());
        assertEquals("sessionVerID", client.sessionVerID(), id.sessionVerID());
        final Reply<SessionReplyStatus> reply = connectionExistsHandler.lastReply();

        testSystem.awaitCompletedReply(reply);
        assertEquals(SessionReplyStatus.OK, reply.resultIfPresent());

        testSystem.await("connection not acquired", connectionAcquiredHandler::invoked);
    }

    void clientTerminatesSession(final BinaryEntryPointClient client)
    {
        client.writeTerminate();
        client.readTerminate();

        client.close();

        assertConnectionDisconnected();
    }

    void acceptorTerminatesSession(final BinaryEntryPointClient client)
    {
        client.readTerminate();
        client.writeTerminate();

        client.assertDisconnected();
        assertConnectionDisconnected();
    }

    void assertConnectionDisconnected()
    {
        testSystem.await("onDisconnect not called", () -> connectionHandler.disconnectReason() != null);
        assertEquals(FixPConnection.State.UNBOUND, connection.state());
    }

    void libraryAcquiresConnection(final BinaryEntryPointClient client)
    {
        libraryAcquiresConnection(client, connectionExistsHandler, connectionAcquiredHandler);
    }
}
