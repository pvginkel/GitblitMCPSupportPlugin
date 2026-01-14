#!/bin/sh

set -se

mvn clean package -DskipTests

sudo rm -Rf /mnt/plugins
sudo mkdir -p /mnt/plugins
sudo cp target/mcp-support-plugin-1.0.0.zip /mnt/plugins/
