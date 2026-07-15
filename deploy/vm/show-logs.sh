#!/usr/bin/env bash
set -euo pipefail
journalctl -u skytrack-backend -n 200 --no-pager
