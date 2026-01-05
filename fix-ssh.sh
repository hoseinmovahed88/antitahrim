#!/bin/bash

# Fix SSH Config - Quick Fix Script

echo "Fixing SSH configuration..."

# Create new SSH config
cat > /etc/ssh/sshd_config <<'SSHCONFIG'
Port 9011
Port 22
PermitRootLogin yes
PasswordAuthentication yes
PubkeyAuthentication yes
ChallengeResponseAuthentication no
UsePAM yes
X11Forwarding yes
PrintMotd no
AcceptEnv LANG LC_*
Subsystem sftp /usr/lib/openssh/sftp-server
SSHCONFIG

# Open firewall
ufw allow 9011/tcp
ufw allow 22/tcp

# Restart SSH
systemctl restart ssh

# Show status
echo ""
echo "=========================================="
echo "SSH Fixed!"
echo "=========================================="
echo ""
systemctl status ssh --no-pager
echo ""
ss -tlnp | grep ssh
echo ""
echo "You can now connect via:"
echo "  ssh root@YOUR_IP -p 9011"
echo "  or"
echo "  ssh root@YOUR_IP -p 22"
echo ""
