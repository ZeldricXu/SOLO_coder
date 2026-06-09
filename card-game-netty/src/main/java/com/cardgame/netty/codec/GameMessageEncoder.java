package com.cardgame.netty.codec;

import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

public class GameMessageEncoder extends MessageToByteEncoder<GameMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx, GameMessage msg, ByteBuf out) throws Exception {
        String json = JsonUtils.toJson(msg);
        if (json == null) {
            json = "{}";
        }
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.writeBytes(data);
    }
}
