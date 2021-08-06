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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.messages.ReplayCompleteEncoder;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class SenderEndPoint
{
    private static final int REPLAY_COMPLETE_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + ReplayCompleteEncoder.BLOCK_LENGTH;

    private final MessageHeaderEncoder messageHeader = new MessageHeaderEncoder();
    private final ReplayCompleteEncoder replayComplete = new ReplayCompleteEncoder();
    private final BufferClaim bufferClaim = new BufferClaim();

    private final ExclusivePublication inboundPublication;

    protected final long connectionId;
    protected int libraryId;

    public SenderEndPoint(final long connectionId, final ExclusivePublication inboundPublication, final int libraryId)
    {
        this.connectionId = connectionId;
        this.inboundPublication = inboundPublication;
        this.libraryId = libraryId;
    }

    public ControlledFragmentHandler.Action onReplayComplete()
    {
        final BufferClaim bufferClaim = this.bufferClaim;
        final long position = inboundPublication.tryClaim(REPLAY_COMPLETE_LENGTH, bufferClaim);

        if (Pressure.isBackPressured(position))
        {
            return ABORT;
        }

        replayComplete
            .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeader)
            .connection(connectionId)
            .libraryId(libraryId);

        bufferClaim.commit();

        return CONTINUE;
    }

    void libraryId(final int libraryId)
    {
        this.libraryId = libraryId;
    }

    public int libraryId()
    {
        return libraryId;
    }

    public long connectionId()
    {
        return connectionId;
    }
}
