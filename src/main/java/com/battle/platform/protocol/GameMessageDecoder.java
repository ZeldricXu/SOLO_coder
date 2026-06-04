package com.battle.platform.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class GameMessageDecoder extends ByteToMessageDecoder {

    private static final int HEADER_SIZE = 4 + 4 + 4 + 8 + 8;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();
        int totalLen = in.readInt();

        if (totalLen < HEADER_SIZE) {
            ctx.close();
            return;
        }

        if (in.readableBytes() < totalLen - 4) {
            in.resetReaderIndex();
            return;
        }

        int msgId = in.readInt();
        int msgType = in.readInt();
        long playerId = in.readLong();
        long timestamp = in.readLong();

        int payloadLen = totalLen - HEADER_SIZE;
        byte[] payload = null;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            in.readBytes(payload);
        }

        GameMessage message = GameMessage.builder()
                .msgId(msgId)
                .msgType(msgType)
                .playerId(playerId)
                .timestamp(timestamp)
                .payload(payload)
                .build();

        out.add(message);
    }
}
