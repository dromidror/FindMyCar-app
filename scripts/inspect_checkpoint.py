import torch

path = '/Users/drordromi/workprojects/FindMyCar-app/checkpoint_gsn_latest.pt'
data = torch.load(path, map_location='cpu', weights_only=False)
sd = data['model_state_dict']

print(f"Total keys: {len(sd)}")
print("\nAll weight/conv layers:")
for k in sorted(sd.keys()):
    v = sd[k]
    if hasattr(v, 'shape') and len(v.shape) >= 2:
        print(f"  {k}: {list(v.shape)}")
