#!/bin/sh

rm -f *~
rm -f *.class
echo "Compiling of Client starting..."
javac FileClient.java
rmic FileClient
echo "Done"

echo "Compiling of Server starting..."
javac FileServer.java
rmic FileServer
echo "done"