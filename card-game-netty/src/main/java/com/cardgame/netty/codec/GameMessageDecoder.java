package com.cardgame.netty.codec;

import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class GameMessageDecoder extends ReplayingDecoder<Void> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int length = in.readInt();
        if (length <= 0 || length > 1024 * 1024 * 10) {
            ctx.close();
            return;
        }
        byte[] data = new byte[length];
        in.readBytes(data);
        String json = new String(data, StandardCharsets.UTF_8);
        GameMessage message = JsonUtils.fromJson(json, GameMessage.class);
        if (message != null) {
            out.add(message);
        }
    }
}
