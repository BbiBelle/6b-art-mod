@echo off
echo ============================================================
echo   Maparts Link - Build Script
echo ============================================================
echo.

:: Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java is not installed or not on your PATH.
    echo         Download Java 21 from https://adoptium.net/ and try again.
    pause
    exit /b 1
)

echo [1/3] Cleaning previous build output...
call gradlew.bat clean --quiet
if errorlevel 1 (
    echo [WARN] Clean step had warnings, continuing...
)

echo [2/3] Compiling and building mod JAR...
call gradlew.bat build
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. See the output above for details.
    pause
    exit /b 1
)

echo.
echo [3/3] Build successful!
echo.
echo Output JAR location:
for %%f in (build\libs\*.jar) do (
    if not "%%~nf"=="%~n0" echo   %%~ff
)
echo.
echo Drop the JAR into your Minecraft mods folder and enjoy!
pause
