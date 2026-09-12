import zipfile, struct

with zipfile.ZipFile('cloudphone-v0.3.6/agentd/libsys_core.so') as z:
    dex = z.read('classes.dex')

string_ids_size, string_ids_off = struct.unpack_from('<II', dex, 56)
type_ids_size, type_ids_off = struct.unpack_from('<II', dex, 64)
proto_ids_size, proto_ids_off = struct.unpack_from('<II', dex, 72)
field_ids_size, field_ids_off = struct.unpack_from('<II', dex, 80)
method_ids_size, method_ids_off = struct.unpack_from('<II', dex, 88)
class_defs_size, class_defs_off = struct.unpack_from('<II', dex, 96)

strings = []
for i in range(string_ids_size):
    str_off, = struct.unpack_from('<I', dex, string_ids_off + i*4)
    pos = str_off
    length, shift = 0, 0
    while True:
        b = dex[pos]
        pos += 1
        length |= (b & 0x7f) << shift
        if (b & 0x80) == 0: break
        shift += 7
    end = dex.find(b'\x00', pos)
    strings.append(dex[pos:end].decode('utf-8', errors='ignore'))

types = [strings[struct.unpack_from('<I', dex, type_ids_off + i*4)[0]] for i in range(type_ids_size)]

methods = []
for i in range(method_ids_size):
    class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', dex, method_ids_off + i*8)
    methods.append(f"{types[class_idx]}->{strings[name_idx]}")

core_methods = [m for m in methods if 'com/android/helper/CoreService' in m]
print('=== CoreService methods ===')
for m in core_methods:
    print(' ', m)

opt_methods = [m for m in methods if 'com/android/helper/Options' in m]
print('\n=== Options methods ===')
for m in opt_methods:
    print(' ', m)

dev_methods = [m for m in methods if 'com/android/helper/device/DesktopConnection' in m]
print('\n=== DesktopConnection methods ===')
for m in dev_methods:
    print(' ', m)
