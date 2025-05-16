#!/bin/bash
set -e

echo "Building Jibri compiler image..."
# Build the builder image
docker build --target builder -t jibri-builder -f Dockerfile.build .

echo "Compiling Jibri..."
# Run the container with the source code mounted to compile Jibri
docker run --name jibri-compile \
  -v "$(pwd):/build" \
  -v "$HOME/.m2:/root/.m2" \
  jibri-builder

# Extract the JAR file from the compilation container
echo "Extracting compiled JAR..."
mkdir -p ./target
docker cp jibri-compile:/build/target/jibri-8.0-SNAPSHOT-jar-with-dependencies.jar ./target/

echo "Building Jitsi Meet + Jibri runtime image..."
# Build the runtime image
docker build -t jitsi-meet-jibri -f Dockerfile.build .

# Create a temporary container from the runtime image
docker create --name jitsi-setup jitsi-meet-jibri

# Copy the specific JAR file to the runtime container
docker cp ./target/jibri-8.0-SNAPSHOT-jar-with-dependencies.jar jitsi-setup:/opt/jibri/lib/jibri.jar

# Commit the container with the JAR installed to create the final image
docker commit jitsi-setup jitsi-meet-jibri
docker rm jitsi-setup
docker rm jibri-compile

echo "Done! The Docker image 'jitsi-meet-jibri' is ready."
echo "You can run it with:"
echo "docker run --privileged -d \\"
echo "  --name jitsi-meet \\"
echo "  -p 80:80 -p 443:443 -p 10000:10000/udp -p 22:22 -p 3478:3478/udp -p 5349:5349 \\"
echo "  -v /dev/shm:/dev/shm \\"
echo "  -v /path/to/recordings:/opt/jibri/recordings \\"
echo "  -e JITSI_HOSTNAME=meet.example.org \\"
echo "  jitsi-meet-jibri"
