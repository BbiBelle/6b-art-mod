#!/bin/bash
echo "============================================================"
echo "  Maparts Link - Build Script"
echo "============================================================"
echo ""

# Check Java
if ! java -version &>/dev/null; then
    echo "[ERROR] Java is not installed or not on your PATH."
    echo "        Download Java 21 from https://adoptium.net/ and try again."
    exit 1
fi

# A JRE alone can't compile; Gradle will auto-download a JDK thanks to the
# foojay toolchain resolver, but a local JDK is faster.
if ! javac -version &>/dev/null; then
    echo "[NOTE] No local JDK found (javac missing) — Gradle will download one."
    echo "       To use a local one instead: sudo apt install openjdk-21-jdk"
fi

# Make gradlew executable
chmod +x gradlew

echo "[1/3] Cleaning previous build output..."
./gradlew clean --quiet

echo "[2/3] Compiling and building mod JAR..."
./gradlew build 
if [ $? -ne 0 ]; then
    echo ""
    echo "[ERROR] Build failed. See the output above for details."
    exit 1
fi

echo ""
echo "[3/3] Build successful!"
echo ""
echo "Output JAR location:"
find build/libs -name "*.jar" | while read f; do
    echo "  $PWD/$f"
done
echo ""
echo "Drop the JAR into your Minecraft mods folder and enjoy!"
