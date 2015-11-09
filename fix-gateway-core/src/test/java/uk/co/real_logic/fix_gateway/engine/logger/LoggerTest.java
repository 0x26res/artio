/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.aeron.driver.MediaDriver;
import uk.co.real_logic.aeron.logbuffer.BlockHandler;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.agrona.CloseHelper;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.agrona.concurrent.NanoClock;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.replication.StreamIdentifier;
import uk.co.real_logic.fix_gateway.streams.Streams;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.driver.Configuration.TERM_BUFFER_LENGTH_PROP_NAME;
import static uk.co.real_logic.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_INT;
import static uk.co.real_logic.agrona.BitUtil.findNextPositivePowerOfTwo;
import static uk.co.real_logic.fix_gateway.TestFixtures.launchMediaDriver;

@RunWith(Parameterized.class)
public class LoggerTest
{

    public static final int SIZE = 8 * 1024;
    public static final int TERM_LENGTH = findNextPositivePowerOfTwo(SIZE * 8);
    public static final int STREAM_ID = 1;
    public static final int OFFSET = 42;
    public static final int VALUE = 43;
    public static final int PATCH_VALUE = 44;
    public static final String CHANNEL = "udp://localhost:9999";

    @Parameters
    public static Collection<Object[]> data()
    {
        return Arrays.asList(new Object[][]{
            {new UnsafeBuffer(ByteBuffer.allocateDirect(SIZE))},
            {new UnsafeBuffer(new byte[SIZE])},
        });
    }

    private final BlockHandler blockHandler = mock(BlockHandler.class);
    private final FragmentHandler fragmentHandler = mock(FragmentHandler.class);
    private final ArgumentCaptor<DirectBuffer> bufferCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
    private final ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);

    private final UnsafeBuffer buffer;

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Logger logger;
    private Archiver archiver;
    private ArchiveReader archiveReader;
    private Publication publication;

    private int work = 0;

    public LoggerTest(final UnsafeBuffer buffer)
    {
        this.buffer = buffer;
    }

    @Before
    public void setUp()
    {
        System.setProperty(TERM_BUFFER_LENGTH_PROP_NAME, String.valueOf(TERM_LENGTH));

        mediaDriver = launchMediaDriver();
        aeron = Aeron.connect(new Aeron.Context());
        final Streams outboundStreams = new Streams(
            CHANNEL, aeron, mock(AtomicCounter.class), STREAM_ID, mock(NanoClock.class), 12000);

        final EngineConfiguration configuration = new EngineConfiguration().logInboundMessages(false);
        logger = new Logger(
            configuration, null, outboundStreams, Throwable::printStackTrace, null, mock(SequenceNumbers.class));

        logger.initArchival();
        archiver = logger.archivers().get(0);
        archiveReader = logger.archiveReader();
        publication = outboundStreams.dataPublication();
    }

    @Test
    public void shouldReadDataThatWasWritten()
    {
        writeAndArchiveBuffer();

        assertCanReadValueAt(HEADER_LENGTH);
    }

    @Test
    public void shouldSupportRotatingFilesAtEndOfTerm()
    {
        archiveBeyondEndOfTerm();

        assertCanReadValueAt(TERM_LENGTH + HEADER_LENGTH);
    }

    @Test
    public void shouldNotReadDataForNotArchivedSession()
    {
        final boolean wasRead = readTo((long) HEADER_LENGTH);

        assertNothingRead(wasRead);
    }

    @Test
    public void shouldNotReadDataForNotArchivedTerm()
    {
        writeAndArchiveBuffer();

        final boolean wasRead = readTo(TERM_LENGTH + HEADER_LENGTH);

        assertNothingRead(wasRead);
    }

    @Test
    public void shouldNotReadNotArchivedDataInCurrentTerm()
    {
        final long endPosition = writeAndArchiveBuffer();

        final boolean wasRead = readTo(endPosition * 2);

        assertNothingRead(wasRead);
    }

    @Test
    public void shouldBlockReadDataThatWasWritten()
    {
        writeAndArchiveBuffer();

        assertCanBlockReadValueAt(HEADER_LENGTH);
    }

    @Test
    public void shouldSupportRotatingFilesAtEndOfTermInBlockRead()
    {
        archiveBeyondEndOfTerm();

        assertCanBlockReadValueAt(TERM_LENGTH + HEADER_LENGTH);
    }

    @Test
    public void shouldNotBlockReadDataForNotArchivedSession()
    {
        final boolean wasRead = readBlockTo((long) HEADER_LENGTH);

        assertNothingBlockRead(wasRead);
    }

    @Test
    public void shouldNotBlockReadDataForNotArchivedTerm()
    {
        writeAndArchiveBuffer();

        final boolean wasRead = readBlockTo(TERM_LENGTH + HEADER_LENGTH);

        assertNothingBlockRead(wasRead);
    }

    @Test
    public void shouldUpdatePosition()
    {
        final long endPosition = writeAndArchiveBuffer();

        assertPosition(endPosition);
    }

    @Test
    public void shouldUpdatePositionDuringRotation()
    {
        final long position = archiveBeyondEndOfTerm();

        assertPosition(position);
    }

    @Test
    public void shouldPatchCurrentTerm()
    {
        writeAndArchiveBuffer();

        patchBuffer(HEADER_LENGTH + OFFSET);

        assertCanReadValueAt(PATCH_VALUE, HEADER_LENGTH);
    }

    @Test
    public void shouldPatchPreviousTerm()
    {
        archiveBeyondEndOfTerm();

        patchBuffer(HEADER_LENGTH + OFFSET);

        assertCanReadValueAt(PATCH_VALUE, HEADER_LENGTH);
    }

    @Ignore // TODO: add writing of header to test
    @Test
    public void shouldPatchMissingTerm()
    {
        archiveBeyondEndOfTerm();

        removeLogFiles();

        patchBuffer(HEADER_LENGTH + OFFSET);

        assertCanReadValueAt(PATCH_VALUE, HEADER_LENGTH);
    }

    private boolean readTo(final long position)
    {
        return archiveReader.read(publication.sessionId(), position, fragmentHandler);
    }

    private boolean readBlockTo(final long position)
    {
        return archiveReader.readBlock(publication.sessionId(), position, SIZE, blockHandler);
    }

    private void removeLogFiles()
    {
        logger
            .directoryDescriptor()
            .listLogFiles(new StreamIdentifier(CHANNEL, STREAM_ID))
            .forEach(File::delete);
    }

    private void assertNothingRead(final boolean wasRead)
    {
        assertFalse("Claimed to read missing data", wasRead);
        verify(fragmentHandler, never()).onFragment(any(), anyInt(), anyInt(), any());
    }

    private void assertNothingBlockRead(final boolean wasRead)
    {
        assertFalse("Claimed to read missing data", wasRead);
        verify(blockHandler, never()).onBlock(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void patchBuffer(final long position)
    {
        final int offset = 1;

        buffer.putInt(offset, PATCH_VALUE);

        archiver.patch(publication.sessionId(), position, buffer, offset, SIZE_OF_INT);
    }

    private void assertPosition(final long endPosition)
    {
        assertEquals(endPosition, archiver.positionOf(publication.sessionId()));
    }

    private long archiveBeyondEndOfTerm()
    {
        long endPosition;

        do
        {
            endPosition = writeAndArchiveBuffer();
        }
        while (endPosition <= TERM_LENGTH);

        return endPosition;
    }

    private long writeAndArchiveBuffer()
    {
        final long endPosition = writeBuffer(VALUE);

        assertDataPublished(endPosition);

        archiveUpTo(endPosition);

        return endPosition;
    }

    private void assertDataPublished(final long endPosition)
    {
        assertThat("Publication has failed an offer", endPosition, greaterThan((long) SIZE));
    }

    private void assertCanReadValueAt(final int position)
    {
        assertCanReadValueAt(VALUE, position);
    }

    private void assertCanReadValueAt(final int value, final long position)
    {
        final boolean hasRead = readTo(position);

        verify(fragmentHandler).onFragment(bufferCaptor.capture(), offsetCaptor.capture(), anyInt(), any());

        assertReadValue(value, position, hasRead);
    }

    private void assertCanBlockReadValueAt(final int position)
    {
        assertCanBlockReadValueAt(VALUE, position);
    }

    private void assertCanBlockReadValueAt(final int value, final long position)
    {
        final boolean hasRead = readBlockTo(position);

        verify(blockHandler).onBlock(
            bufferCaptor.capture(), offsetCaptor.capture(), eq(SIZE), eq(publication.sessionId()), anyInt());

        assertReadValue(value, position, hasRead);
    }

    private void assertReadValue(final int value, final long position, final boolean hasRead)
    {
        assertEquals(value, bufferCaptor.getValue().getInt(offsetCaptor.getValue() + OFFSET));
        assertTrue("Failed to read value at " + position, hasRead);
    }

    private long writeBuffer(final int value)
    {
        buffer.putInt(OFFSET, value);

        long endPosition;
        do
        {
            endPosition = publication.offer(buffer, 0, SIZE);
            LockSupport.parkNanos(100);
        }
        while (endPosition < 0);

        return endPosition;
    }

    private void archiveUpTo(final long endPosition)
    {
        do
        {
            work += archiver.doWork();
        } while (work < endPosition);
    }

    @After
    public void tearDown()
    {
        CloseHelper.close(logger);
        CloseHelper.close(aeron);
        CloseHelper.close(mediaDriver);

        System.gc();
    }

}
