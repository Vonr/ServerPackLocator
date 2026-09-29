package net.forgecraft.serverpacklocator.secure;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import net.forgecraft.serverpacklocator.ConfigException;
import org.apache.http.client.methods.RequestBuilder;

import javax.annotation.Nullable;

public interface IConnectionSecurityManager
{
    void onClientConnectionCreation(RequestBuilder requestBuilder);

    default void onAuthenticateComplete(String challengeString) {
    }

    default void authenticateConnection(RequestBuilder requestBuilder) {
    }

    boolean onServerConnectionRequest(ChannelHandlerContext ctx, FullHttpRequest msg);

    void initialize(SecurityConfig config) throws ConfigException;

    void onServerResponse(ChannelHandlerContext ctx, HttpRequest msg, HttpResponse resp);

    static IConnectionSecurityManager create(SecurityConfig config) throws ConfigException {
        var securityType = config.getType();
        if (securityType == null) {
            throw new ConfigException("No securityType is set.");
        }

        var securityManager = switch (securityType) {
            case PASSWORD -> PasswordBasedSecurityManager.getInstance();
            case PUBLICKEY -> ProfileKeyPairBasedSecurityManager.getInstance();
        };

        securityManager.initialize(config);
        return securityManager;
    }

    @Nullable
    String getUnavailabilityReason();
}
