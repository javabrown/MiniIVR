# MiniIVR

Local FreeSWITCH IVR on Docker.

Basic telephony and IVR with the minimum possible setup.

## What It Does

Call flow:

Linphone
   |
   v
FreeSWITCH
   |
   v
Dial 9199
   |
   v
"Press 1 for Sales, Press 2 for Support"
   |
   +-- Press 1 --> Sales message
   |
   +-- Press 2 --> Support message


## Project Structure

MiniIVR/
├── Dockerfile
├── run.sh
└── generated/
    ├── 99-mini-ivr.xml
    ├── menu.wav
    ├── sales.wav
    ├── support.wav
    └── invalid.wav


## Requirements

Install:

1. Docker Desktop
2. Linphone

Docker runs FreeSWITCH.

Linphone acts as the SIP phone used to call the IVR.


## Start MiniIVR

Make the startup script executable:

```bash
chmod +x run.sh

Troubeshoot/Debug call by logging into fs_cli 

docker exec -ti miniivr fs_cli


~~~~~~~~~
Load `mod_curl` to make HTTP/REST requests directly from a dialplan

## Verify `mod_curl` installation ==>  docker exec miniivr fs_cli -x 'module_exists mod_curl'

## If not install, Install `mod_curl` ==>  docker exec miniivr fs_cli -x 'load mod_curl'