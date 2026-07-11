package com.carina.server;

import com.carina.server.service.DatabaseService;
import com.carina.server.service.DatabaseServiceImpl;
import com.jiashi.rpc.core.config.RpcConfig;
import com.jiashi.rpc.core.provider.LocalRegistry;
import com.jiashi.rpc.core.transport.server.NettyServer;
import java.io.File;

public class ServerMain {
    public static void main(String[] args) {
        String projectRoot = System.getProperty("user.dir");
        String dbDir = projectRoot + File.separator + "carina_data_dir";
        
        System.out.println("Starting CarinaServer JNA integration testing...");
        DatabaseService dbService = new DatabaseServiceImpl(dbDir);
        
        // Register locally for OrionRPC (so the server knows how to route the request)
        LocalRegistry.register(DatabaseService.class.getName(), dbService);
        
        RpcConfig config = RpcConfig.getInstance();
        config.setServerHost("127.0.0.1");
        config.setServerPort(8080);
        
        // Register remotely to Zookeeper (so the client can discover it)
        try {
            com.jiashi.rpc.common.extension.ExtensionLoader<com.jiashi.rpc.core.registry.ServiceRegistry> loader =
                    com.jiashi.rpc.common.extension.ExtensionLoader.getExtensionLoader(com.jiashi.rpc.core.registry.ServiceRegistry.class);
            com.jiashi.rpc.core.registry.ServiceRegistry registry = loader.getExtension(config.getRegistryType());
            registry.registerService(DatabaseService.class.getName(), new java.net.InetSocketAddress(config.getServerHost(), config.getServerPort()));
            System.out.println("Service registered to Zookeeper successfully.");
        } catch (Exception e) {
            System.err.println("Failed to register service to ZK: " + e.getMessage());
        }
        
        System.out.println("Starting OrionRPC NettyServer on port 8080...");
        NettyServer server = new NettyServer(config);
        server.start();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            ((DatabaseServiceImpl)dbService).close();
        }));
    }
}
