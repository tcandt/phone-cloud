import re

with open('cloudphone-v0.3.6/agentd/cloudphone-agent-amd64', 'rb') as f:
    data = f.read()

json_tags = set(re.findall(rb'json:"([^"]+)"', data))
print('Total json tags in agent:', len(json_tags))
print('\nTop JSON tags in agent:')
for t in sorted(json_tags)[:80]:
    print(' ', t.decode('latin1'))

# Find message type / action strings
print('\nKey message keywords in agent:')
keywords = [b'register', b'heartbeat', b'offer', b'answer', b'candidate', b'preview', b'snapshot', b'command', b'inject', b'touch', b'scroll', b'keycode']
for kw in keywords:
    matches = set(re.findall(rb'["\']([a-zA-Z0-9_\-]*' + kw + rb'[a-zA-Z0-9_\-]*)["\']', data))
    if matches:
        print(f'  {kw.decode()}: {[m.decode("latin1") for m in matches][:10]}')
