#!/bin/bash

URL=$1
COUNT=${2:-45}

if [ -z "$URL" ]; then
  echo "Usage: ./traffic-test.sh <url> [count]"
  exit 1
fi

SUCCESS=0
FAIL=0

for i in $(seq 1 $COUNT); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$URL")

  NOW=$(date +"%H:%M:%S")
  echo "[$NOW] status=$CODE"

  if [ "$CODE" = "200" ]; then
    SUCCESS=$((SUCCESS + 1))
  else
    FAIL=$((FAIL + 1))
  fi

  sleep 1
done

echo "----------------------"
echo "success=$SUCCESS"
echo "fail=$FAIL"
echo "total=$COUNT"
