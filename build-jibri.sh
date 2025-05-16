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

# Create a directory for compiled artifacts if it doesn't exist
mkdir -p ./target

# Copy the JAR file
echo "Building runtime image with Jibri installed..."
# Build the runtime image
docker build --target 0 -t jibri-runtime -f Dockerfile.build .

# Create a temporary container from the runtime image
docker create --name jibri-setup jibri-runtime

# Copy the compiled JAR from our local target directory
docker cp ./target/jibri*.jar jibri-setup:/opt/jibri/lib/jibri.jar

# Commit the container with the JAR installed to create the final image
docker commit jibri-setup jibri-runtime
docker rm jibri-setup
docker rm jibri-compile

echo "Done! The Docker image 'jibri-runtime' is ready."
echo "You can run it with:"
echo "docker run --privileged -d \\"
echo "  -v /dev/shm:/dev/shm \\"
echo "  -v /path/to/recordings:/opt/jibri/recordings \\"
echo "  -v /path/to/custom/jibri.conf:/etc/jitsi/jibri/jibri.conf \\"
echo "  jibri-runtime"
