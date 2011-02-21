#!/bin/sh
CASSANDRA_HOME=/home/tst/Work/Cassandra
CASSANDRA_NODES="cassandra-1 cassandra-2 cassandra-3"

for NODE in $CASSANDRA_NODES; do
	rm -rf $CASSANDRA_HOME/$NODE/data/*
done
