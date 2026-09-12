import re

with open('cloudphone-v0.3.6/bin/windows_amd64/webrtc-signaling.exe', 'rb') as f:
    data = f.read()

# find json tags
json_tags = set(re.findall(rb'json:"([^"]+)"', data))
print('Total json tags:', len(json_tags))
print('\nTop JSON tags:')
for t in sorted(json_tags)[:60]:
    print(' ', t.decode('latin1'))

# find message_type occurrences
msg_types = set(re.findall(rb'"message_type"\s*:\s*"([^"]+)"', data))
print('\nMessage types found in strings:', msg_types)

# Also check string literals around message_type
literals = set(re.findall(rb'message_type|device_msg|device_info|register_device|inject_data|group_control', data))
print('\nLiterals:', literals)
