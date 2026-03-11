@echo off
echo ========================================
echo   Syncing www changes to Android...
echo ========================================
echo.

call npx cap copy android

echo.
echo ========================================
echo   Done! Now in Android Studio:
echo   Build > Build APK(s)
echo ========================================
echo.
pause
