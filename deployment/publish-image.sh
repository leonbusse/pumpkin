#!/bin/bash

export PUMPKIN_VERSION=0.0.1

docker login docker.io

./gradlew :build
docker build -t pumpkin-$PUMPKIN_VERSION .
docker tag pumpkin-$PUMPKIN_VERSION leonbusse/pumpkin:$PUMPKIN_VERSION
docker push leonbusse/pumpkin:$PUMPKIN_VERSION