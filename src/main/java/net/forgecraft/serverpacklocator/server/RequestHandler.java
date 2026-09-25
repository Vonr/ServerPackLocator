package net.forgecraft.serverpacklocator.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import net.forgecraft.serverpacklocator.ModAccessor;
import net.forgecraft.serverpacklocator.secure.IConnectionSecurityManager;
import net.forgecraft.serverpacklocator.utils.CompressionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final ServerFileManager serverFileManager;
    private static final Logger LOGGER = LogManager.getLogger();
    private final IConnectionSecurityManager connectionSecurityManager;

    RequestHandler(IConnectionSecurityManager connectionSecurityManager, ServerFileManager serverFileManager) {
        this.serverFileManager = serverFileManager;
        this.connectionSecurityManager = connectionSecurityManager;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
        if (Objects.equals(HttpMethod.GET, msg.method())) {
            handleGet(ctx, msg);
        } else {
            buildReply(ctx, msg, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed");
        }
    }
    private void handleGet(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
        if (!this.connectionSecurityManager.validateServerRequest(ctx, msg)) {
            LOGGER.warn("Received unauthorized request.");
            build401(ctx, msg);
            return;
        }

        if (Objects.equals("/servermanifest.json", msg.uri())) {
            LOGGER.info("Manifest request for client {}", determineClientIp(ctx, msg));
            buildReply(ctx, msg, HttpResponseStatus.OK, "application/json", serverFileManager.getManifestJson());
        } else if (msg.uri().startsWith("/files/")) {
            String fileName = URLDecoder.decode(msg.uri().substring(7), StandardCharsets.UTF_8);
            ServerFileManager.ExposedFile exposedFile = serverFileManager.getExposedFile(fileName);
            if (exposedFile == null) {
                LOGGER.debug("Requested file {} not found or not exposed", fileName);
                build404(ctx, msg);
            } else {
                buildFileReply(ctx, msg, exposedFile);
            }
        } else if (connectionSecurityManager.needsAuthRequest() && Objects.equals("/authenticate", msg.uri())) {
            LOGGER.info("Authentication request for client {}", determineClientIp(ctx, msg));
            buildReply(ctx, msg, HttpResponseStatus.OK, "text/plain", "Authentication started.");
        } else {
            LOGGER.debug("Failed to understand message {}", msg);
            build404(ctx, msg);
        }
    }

    private String determineClientIp(final ChannelHandlerContext ctx, final FullHttpRequest msg)
    {
        if (!ModAccessor.isLogIps()) {
            return "[IP hidden]";
        }

        if (msg.headers().contains("X-Forwarded-For"))
            return String.join(" via ", msg.headers().getAll("X-Forwarded-For")) + " (using Remote Address: " + ctx.channel().remoteAddress().toString() + ")";

        if (msg.headers().contains("Forwarded-For"))
            return String.join(" via ", msg.headers().getAll("Forwarded-For")) + " (using Remote Address: " + ctx.channel().remoteAddress().toString() + ")";

        return ctx.channel().remoteAddress().toString();
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        if (!(cause.getCause() instanceof SSLException)) {
            LOGGER.warn("Error in request handler code", cause);
        } else {
            LOGGER.trace("SSL error in handling code", cause.getCause());
        }
    }

    private void build404(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
        buildReply(ctx, msg, HttpResponseStatus.NOT_FOUND, "text/plain", "Not Found");
    }

    private void build401(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
        buildReply(ctx, msg, HttpResponseStatus.UNAUTHORIZED, "text/plain", "Unauthorized");
    }

    private void buildReply(final ChannelHandlerContext ctx, final FullHttpRequest msg, final HttpResponseStatus status, final String contentType, final String message) {
        final ByteBuf content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        HttpUtil.setKeepAlive(resp, HttpUtil.isKeepAlive(msg));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setContentLength(resp, content.writerIndex());

        this.connectionSecurityManager.decorateServerResponse(ctx, msg, resp);
        ctx.writeAndFlush(resp);
    }

    private void buildFileReply(final ChannelHandlerContext ctx, final FullHttpRequest msg, final ServerFileManager.ExposedFile file) {
        ByteBuf content = Unpooled.buffer();

        var usedEncodingsStr = "";
        try (OutputStream contentStream = new ByteBufOutputStream(content)) {
            var out = contentStream;
            var acceptedEncodings = msg.headers().get("Accept-Encoding", "").split(",");
            var usedEncodings = new StringBuilder();
            for (var accepted : acceptedEncodings) {
                var method = CompressionUtils.methodFromName(accepted.trim());
                if (method != null) {
                    out = method.compress(out);
                    if (!usedEncodings.isEmpty()) {
                        usedEncodings.append(',');
                    }
                    usedEncodings.append(method.name());
                }
            }

            try (var fileStream = Files.newInputStream(file.path())) {
                fileStream.transferTo(out);
            }

            usedEncodingsStr = usedEncodings.toString();
            out.flush();
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        LOGGER.debug("Sending {} with {} compression ({} -> {} bytes)", file.name(), usedEncodingsStr.isEmpty() ? "no" : usedEncodingsStr, file.size(), content.writerIndex());

        final FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        HttpUtil.setKeepAlive(response, HttpUtil.isKeepAlive(msg));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        response.headers().set("filename", file.name());
        if (!usedEncodingsStr.isEmpty()) {
            response.headers().set("Content-Encoding", usedEncodingsStr);
        }
        HttpUtil.setContentLength(response, content.writerIndex());

        ctx.writeAndFlush(response);
    }
}
