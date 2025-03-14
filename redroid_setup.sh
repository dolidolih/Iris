#!/bin/bash

# 1. Check and Install Docker
if command -v docker &> /dev/null
then
    echo "Docker already installed. Skipping docker install."
else
    echo "Installing Docker..."
    sudo apt-get update
    sudo apt-get install ca-certificates curl -y
    sudo install -m 0755 -d /etc/apt/keyrings
    sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    sudo chmod a+r /etc/apt/keyrings/docker.asc

    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    sudo apt-get update
    sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin -y
    echo "Docker installed successfully."
fi

# 2. Set up binder drivers
echo "Setting up binder drivers."

# binder.service
cat <<EOF > binder.service
[Unit]
Description=Auto load binder
After=network-online.target

[Service]
Type=oneshot
ExecStart=/sbin/modprobe binder_linux devices="binder,hwbinder,vndbinder"

[Install]
WantedBy=multi-user.target
EOF

sudo cp binder.service /etc/systemd/system/binder.service
rm binder.service

sudo systemctl enable binder.service
sudo systemctl start binder.service

# 3. Create redoid docker container
echo "Creating redoid docker container... It will take a few minutes."
sudo docker run -itd --privileged --name redroid \
    -v ~/data:/data \
    -p 5555:5555 \
    -p 3000:3000 \
    redroid/redroid:11.0.0-latest \
    ro.product.model=SM-T970 \
    ro.product.brand=Samsung

# 4. Final message
echo "Redroid installation finished."