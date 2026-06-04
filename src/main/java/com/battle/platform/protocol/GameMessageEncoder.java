package com.battle.platform.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class GameMessageEncoder extends MessageToByteEncoder<GameMessage> {

    private static final int HEADER_SIZE = 4 + 4 + 8 + 8;

    @Override
    protected void encode(ChannelHandlerContext ctx, GameMessage msg, ByteBuf out) {
        int payloadLen = msg.getPayload() != null ? msg.getPayload().length : 0;
        int totalLen = HEADER_SIZE + payloadLen;

        out.writeInt(totalLen);
        out.writeInt(msg.getMsgId());
        out.writeInt(msg.getMsgType());
        out.writeLong(msg.getPlayerId());
        out.writeLong(msg.getTimestamp());

        if (payloadLen > 0) {
            out.writeBytes(msg.getPayload());
        }
    }
}
