package com.carina.client;

import com.carina.server.service.DatabaseService;
import com.jiashi.rpc.core.config.RpcConfig;
import com.jiashi.rpc.core.transport.client.NettyClient;
import com.jiashi.rpc.core.transport.client.proxy.RpcClientProxy;
import com.jiashi.rpc.core.registry.ServiceDiscovery;
import com.jiashi.rpc.core.registry.ServiceInstance;

import java.util.Collections;
import java.util.List;

public class ClientMain {
    public static void main(String[] args) {
        System.out.println("Starting CarinaClient JNA integration testing...");
        
        // Initialize RpcConfig
        RpcConfig config = RpcConfig.getInstance();
        config.setLoadBalanceType("roundrobin"); // Fix case mismatch with ExtensionLoader
        
        System.out.println("Creating NettyClient...");
        NettyClient nettyClient = new NettyClient();
        
        System.out.println("Creating RpcClientProxy...");
        RpcClientProxy proxy = new RpcClientProxy(nettyClient);
        
        DatabaseService dbService = proxy.getProxy(DatabaseService.class);
        
        System.out.println("Sending PUT request to OrionRPC Server...");
        dbService.put("hello_jna", "world_jna");
        System.out.println("PUT request completed.");
        
        System.out.println("Sending GET request to OrionRPC Server...");
        String val = dbService.get("hello_jna");
        System.out.println("GET request returned: " + val);
        
        System.out.println("\n--- Testing Vector KV ---");
        System.out.println("Sending 3 vector records to OrionRPC Server...");
        dbService.putVector("dog", "golden retriever", new float[]{1.0f, 0.0f, 0.0f});
        dbService.putVector("cat", "british shorthair", new float[]{0.9f, 0.1f, 0.0f});
        dbService.putVector("car", "tesla model 3", new float[]{0.0f, 1.0f, 0.0f});
        System.out.println("Vector records inserted successfully.");
        
        System.out.println("Sending vector search request (query: [0.95, 0.05, 0.0])...");
        String searchResult = dbService.searchVector(new float[]{0.95f, 0.05f, 0.0f}, 2);
        System.out.println("Search Vector request returned: " + searchResult);
        
        System.exit(0);
    }
}
