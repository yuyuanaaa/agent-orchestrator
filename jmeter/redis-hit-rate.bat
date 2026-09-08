@echo off
REM Redis cache hit-rate snapshot.
REM Run this ONCE BEFORE the test and ONCE AFTER the test, then subtract.
REM
REM   hit_rate = (hits_after - hits_before)
REM              / ( (hits_after - hits_before) + (misses_after - misses_before) )
REM
REM Note: project uses Redis database index 1.

echo.
echo ===== Redis stats snapshot =====
docker exec agent-redis redis-cli -n 1 info stats 2>nul | findstr /r "keyspace_hits keyspace_misses"
if errorlevel 1 (
  echo [ERROR] Cannot reach Redis container "agent-redis".
  echo Make sure Docker Desktop is running and: docker compose up -d mysql redis
)
echo.
pause
