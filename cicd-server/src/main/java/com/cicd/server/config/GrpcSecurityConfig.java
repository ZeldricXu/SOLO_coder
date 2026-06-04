package com.cicd.server.config;

import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

@Slf4j
@Configuration
public class GrpcSecurityConfig {

    @Value("${grpc.security.mtls.enabled:false}")
    private boolean mtlsEnabled;

    @Value("${grpc.security.mtls.key-store-path:}")
    private String keyStorePath;

    @Value("${grpc.security.mtls.key-store-password:}")
    private String keyStorePassword;

    @Value("${grpc.security.mtls.trust-store-path:}")
    private String trustStorePath;

    @Value("${grpc.security.mtls.trust-store-password:}")
    private String trustStorePassword;

    @Bean
    @ConditionalOnProperty(name = "grpc.security.mtls.enabled", havingValue = "true")
    public GrpcServerConfigurer mtlsServerConfigurer() {
        return serverBuilder -> {
            try {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
                try (InputStream keyStoreStream = new FileInputStream(keyStorePath)) {
                    KeyStore keyStore = KeyStore.getInstance("PKCS12");
                    keyStore.load(keyStoreStream, keyStorePassword.toCharArray());
                    kmf.init(keyStore, keyStorePassword.toCharArray());
                }

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
                try (InputStream trustStoreStream = new FileInputStream(trustStorePath)) {
                    KeyStore trustStore = KeyStore.getInstance("PKCS12");
                    trustStore.load(trustStoreStream, trustStorePassword.toCharArray());
                    tmf.init(trustStore);
                }

                if (serverBuilder instanceof io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder nettyBuilder) {
                    nettyBuilder.sslContext(
                        io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
                            .forServer(kmf)
                            .trustManager(tmf)
                            .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
                            .build()
                    );
                }

                log.info("gRPC mTLS enabled - server requires client certificates");
            } catch (Exception e) {
                log.error("Failed to configure gRPC mTLS", e);
                throw new RuntimeException("gRPC mTLS configuration failed", e);
            }
        };
    }
}
