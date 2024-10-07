#!/usr/bin/env bash

sudo apt -y update -qq
sudo apt -y upgrade -qq
sudo apt -y dist-upgrade
sudo apt -y autoremove

exit 0;