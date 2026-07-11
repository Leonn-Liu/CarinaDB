#!/bin/bash

# Ensure we're in the right directory
cd /Users/yufann/Developer/projects/CarinaDB

# Step 1: Compile the C++ Engine (libcarina_engine.dylib)
echo "Building C++ Engine shared library..."
mkdir -p build && cd build
cmake .. && make carina_engine_shared
cd ..

# Make sure JNA can find the library
export DYLD_LIBRARY_PATH=$(pwd)/build/carina-engine:$DYLD_LIBRARY_PATH
export LD_LIBRARY_PATH=$(pwd)/build/carina-engine:$LD_LIBRARY_PATH
# JNA specifically uses jna.library.path
export MAVEN_OPTS="-Djna.library.path=$(pwd)/build/carina-engine"

# Step 2: Compile Java Server and Client
echo "Compiling Java Server..."
cd carina-server
mvn clean compile
cd ..

echo "Compiling Java Client..."
cd carina-client
mvn clean compile
cd ..

# Reminder for ZK
echo "=========================================================================="
echo "⚠️ IMPORTANT: OrionRPC uses Zookeeper. "
echo "If your Zookeeper is not running at 127.0.0.1:2181, the Server/Client will fail."
echo "Please make sure Zookeeper is running before starting the tests."
echo "=========================================================================="

echo "You can now run the Server and Client using Maven (e.g. mvn exec:java ...)"
echo "To test manually in two different terminals:"
echo "Terminal 1 (Server): cd carina-server && export MAVEN_OPTS=\"-Djna.library.path=$(pwd)/build/carina-engine\" && mvn exec:java -Dexec.mainClass=\"com.carina.server.ServerMain\""
echo "Terminal 2 (Client): cd carina-client && mvn exec:java -Dexec.mainClass=\"com.carina.client.ClientMain\""
