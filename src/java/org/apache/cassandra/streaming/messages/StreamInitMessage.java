/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.streaming.messages;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.UUID;

import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.io.util.DataOutputBufferFixed;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.net.CompactEndpointSerializationHelper;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.streaming.StreamOperation;
import org.apache.cassandra.utils.UUIDSerializer;

/**
 * StreamInitMessage is first sent from the node where {@link org.apache.cassandra.streaming.StreamSession} is started,
 * to initiate corresponding {@link org.apache.cassandra.streaming.StreamSession} on the other side.
 */
public class StreamInitMessage
{
    public static IVersionedSerializer<StreamInitMessage> serializer = new StreamInitMessageSerializer();

    public final InetAddress from;
    public final int sessionIndex;
    public final UUID planId;
    public final StreamOperation streamOperation;

    // true if this init message is to connect for outgoing message on receiving side
    public final boolean isForOutgoing;
    public final boolean keepSSTableLevel;
    public final boolean isIncremental;
    public final UUID pendingRepair;

    public StreamInitMessage(InetAddress from, int sessionIndex, UUID planId, StreamOperation streamOperation, boolean isForOutgoing, boolean keepSSTableLevel, boolean isIncremental, UUID pendingRepair)
    {
        this.from = from;
        this.sessionIndex = sessionIndex;
        this.planId = planId;
        this.streamOperation = streamOperation;
        this.isForOutgoing = isForOutgoing;
        this.keepSSTableLevel = keepSSTableLevel;
        this.isIncremental = isIncremental;
        this.pendingRepair = pendingRepair;
    }

    /**
     * Create serialized message.
     *
     * @param compress true if message is compressed
     * @param version Streaming protocol version
     * @return serialized message in ByteBuffer format
     */
    public ByteBuffer createMessage(boolean compress, int version)
    {
        int header = 0;
        // set compression bit.
        if (compress)
            header |= 4;
        // set streaming bit
        header |= 8;
        // Setting up the version bit
        header |= (version << 8);

        byte[] bytes;
        try
        {
            int size = (int)StreamInitMessage.serializer.serializedSize(this, version);
            try (DataOutputBuffer buffer = new DataOutputBufferFixed(size))
            {
                StreamInitMessage.serializer.serialize(this, buffer, version);
                bytes = buffer.getData();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        assert bytes.length > 0;

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + bytes.length);
        buffer.putInt(MessagingService.PROTOCOL_MAGIC);
        buffer.putInt(header);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private static class StreamInitMessageSerializer implements IVersionedSerializer<StreamInitMessage>
    {
        public void serialize(StreamInitMessage message, DataOutputPlus out, int version) throws IOException
        {
            CompactEndpointSerializationHelper.serialize(message.from, out);
            out.writeInt(message.sessionIndex);
            UUIDSerializer.serializer.serialize(message.planId, out, MessagingService.current_version);
            out.writeUTF(message.streamOperation.getDescription());
            out.writeBoolean(message.isForOutgoing);
            out.writeBoolean(message.keepSSTableLevel);
            out.writeBoolean(message.isIncremental);

            out.writeBoolean(message.pendingRepair != null);
            if (message.pendingRepair != null)
            {
                UUIDSerializer.serializer.serialize(message.pendingRepair, out, MessagingService.current_version);
            }
        }

        public StreamInitMessage deserialize(DataInputPlus in, int version) throws IOException
        {
            InetAddress from = CompactEndpointSerializationHelper.deserialize(in);
            int sessionIndex = in.readInt();
            UUID planId = UUIDSerializer.serializer.deserialize(in, MessagingService.current_version);
            String description = in.readUTF();
            boolean sentByInitiator = in.readBoolean();
            boolean keepSSTableLevel = in.readBoolean();

            boolean isIncremental = in.readBoolean();
            UUID pendingRepair = in.readBoolean() ? UUIDSerializer.serializer.deserialize(in, version) : null;
            return new StreamInitMessage(from, sessionIndex, planId, StreamOperation.fromString(description), sentByInitiator, keepSSTableLevel, isIncremental, pendingRepair);
        }

        public long serializedSize(StreamInitMessage message, int version)
        {
            long size = CompactEndpointSerializationHelper.serializedSize(message.from);
            size += TypeSizes.sizeof(message.sessionIndex);
            size += UUIDSerializer.serializer.serializedSize(message.planId, MessagingService.current_version);
            size += TypeSizes.sizeof(message.streamOperation.getDescription());
            size += TypeSizes.sizeof(message.isForOutgoing);
            size += TypeSizes.sizeof(message.keepSSTableLevel);
            size += TypeSizes.sizeof(message.isIncremental);
            size += TypeSizes.sizeof(message.pendingRepair != null);
            if (message.pendingRepair != null)
            {
                size += UUIDSerializer.serializer.serializedSize(message.pendingRepair, MessagingService.current_version);
            }
            return size;
        }
    }
}
