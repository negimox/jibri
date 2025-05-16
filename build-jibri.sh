#!/bin/bash
set -e

# Build the Docker image
docker build -t jibri-builder -f Dockerfile.build .

# Run the container with the source code mounted
docker run --rm \
  -v "$(pwd):/build" \
  -v "$HOME/.m2:/root/.m2" \
  jibri-builder

echo "Build completed. Artifacts are in the target/ directory."
