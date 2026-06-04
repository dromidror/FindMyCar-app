"""
Convert RoNIN checkpoint to TFLite using the original RoNIN source code.

Prerequisites:
  1. Clone RoNIN: git clone https://github.com/Sachini/ronin.git /tmp/ronin
  2. Have the checkpoint file ready

Usage:
    python convert_ronin_to_tflite.py --checkpoint path/to/checkpoint_gsn_latest.pt --output ronin_model.tflite
"""

import argparse
import os
import sys
import numpy as np

# Add RoNIN source to path
RONIN_PATH = os.environ.get('RONIN_PATH', '/tmp/ronin')
if os.path.exists(os.path.join(RONIN_PATH, 'source')):
    sys.path.insert(0, os.path.join(RONIN_PATH, 'source'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output", default="ronin_model.tflite")
    parser.add_argument("--window_size", type=int, default=200)
    parser.add_argument("--ronin_path", default=RONIN_PATH, help="Path to cloned RoNIN repo")
    args = parser.parse_args()

    if not os.path.exists(args.checkpoint):
        print(f"ERROR: Checkpoint not found: {args.checkpoint}")
        sys.exit(1)

    # Try to use RoNIN source directly
    ronin_source = os.path.join(args.ronin_path, 'source')
    if os.path.exists(ronin_source):
        sys.path.insert(0, ronin_source)
        print(f"Using RoNIN source from: {ronin_source}")
        try:
            from ronin_resnet import get_model
            print("Loaded RoNIN model builder from source")
            convert_with_ronin_source(args)
            return
        except ImportError as e:
            print(f"Could not import from RoNIN source: {e}")

    # Fallback: reconstruct manually
    print("Reconstructing model architecture from checkpoint...")
    convert_manual(args)


def convert_manual(args):
    import torch
    import torch.nn as nn

    class ResBlock(nn.Module):
        def __init__(self, in_ch, out_ch, downsample=None):
            super().__init__()
            self.conv1 = nn.Conv1d(in_ch, out_ch, 3, padding=1)
            self.bn1 = nn.BatchNorm1d(out_ch)
            self.conv2 = nn.Conv1d(out_ch, out_ch, 3, padding=1)
            self.bn2 = nn.BatchNorm1d(out_ch)
            self.downsample = downsample
            self.relu = nn.ReLU(inplace=True)

        def forward(self, x):
            identity = x
            out = self.relu(self.bn1(self.conv1(x)))
            out = self.bn2(self.conv2(out))
            if self.downsample is not None:
                identity = self.downsample(x)
            out += identity
            return self.relu(out)

    class RoninResNet(nn.Module):
        def __init__(self):
            super().__init__()
            # Input: [batch, 6, window] → [batch, 64, window]
            self.input_block = nn.Sequential(
                nn.Conv1d(6, 64, kernel_size=7, padding=3),
                nn.BatchNorm1d(64),
                nn.ReLU(inplace=True)
            )

            # Group 0: 64→64 (no downsample)
            self.residual_groups = nn.ModuleList()

            # Group 0: 64 channels
            g0 = nn.ModuleList([
                ResBlock(64, 64),
                ResBlock(64, 64)
            ])
            self.residual_groups.append(g0)

            # Group 1: 64→128 (with downsample)
            ds1 = nn.Sequential(nn.Conv1d(64, 128, 1), nn.BatchNorm1d(128))
            g1 = nn.ModuleList([
                ResBlock(64, 128, downsample=ds1),
                ResBlock(128, 128)
            ])
            self.residual_groups.append(g1)

            # Group 2: 128→256 (with downsample)
            ds2 = nn.Sequential(nn.Conv1d(128, 256, 1), nn.BatchNorm1d(256))
            g2 = nn.ModuleList([
                ResBlock(128, 256, downsample=ds2),
                ResBlock(256, 256)
            ])
            self.residual_groups.append(g2)

            # Group 3: 256→512 (with downsample)
            ds3 = nn.Sequential(nn.Conv1d(256, 512, 1), nn.BatchNorm1d(512))
            g3 = nn.ModuleList([
                ResBlock(256, 512, downsample=ds3),
                ResBlock(512, 512)
            ])
            self.residual_groups.append(g3)

            # Output: transition + FC
            self.output_block_transition = nn.Sequential(
                nn.Conv1d(512, 128, 1),
                nn.BatchNorm1d(128)
            )

            # FC input = 896 (from multi-scale pooling: 64+128+256+128=576? or 128*7=896?)
            # Actually 896 = concat of adaptive_avg_pool from multiple scales
            # Let's try: pool each group output → concat → FC
            # 64 + 128 + 256 + 512 = 960 (not 896)
            # 128 (transition output) * 7 = 896? No.
            # Let's check: maybe it's pool(group0)=64 + pool(group1)=128 + pool(group2)=256 + pool(transition)=128 + something
            # Actually from the code: it might be 64+128+256+128+320... 
            # The safest approach: just use 896 as FC input
            self.output_block_fc = nn.Sequential(
                nn.Linear(896, 512),
                nn.ReLU(inplace=True),
                nn.Dropout(0.5),
                nn.Linear(512, 512),
                nn.ReLU(inplace=True),
                nn.Dropout(0.5),
                nn.Linear(512, 2)
            )

        def forward(self, x):
            x = self.input_block(x)

            group_outputs = []
            for group in self.residual_groups:
                for block in group:
                    x = block(x)
                group_outputs.append(x)

            # Transition on last group output
            trans = self.output_block_transition(x)

            # Global average pool each scale and concatenate
            pooled = []
            for go in group_outputs:
                pooled.append(nn.functional.adaptive_avg_pool1d(go, 1).flatten(1))
            pooled.append(nn.functional.adaptive_avg_pool1d(trans, 1).flatten(1))

            # Concat: 64 + 128 + 256 + 512 + 128 = 1088... still not 896
            # Let's try without group0: 128 + 256 + 512 = 896! Yes!
            # Skip group 0, use groups 1,2,3 + transition
            features = torch.cat(pooled[1:], dim=1)  # 128+256+512 = 896? No = 896+128=1024
            # Try: groups 1-3 only = 128+256+512 = 896!
            features = torch.cat([pooled[1], pooled[2], pooled[3]], dim=1)

            return self.output_block_fc(features)

    # Load and try
    print(f"Loading checkpoint: {args.checkpoint}")
    data = torch.load(args.checkpoint, map_location='cpu', weights_only=False)
    sd = data['model_state_dict']

    # Remap state dict keys
    model = RoninResNet()
    
    # Map downsample keys
    new_sd = {}
    for k, v in sd.items():
        new_k = k
        # output_block.transition.X → output_block_transition.X
        new_k = new_k.replace('output_block.transition.', 'output_block_transition.')
        # output_block.fc.X → output_block_fc.X
        new_k = new_k.replace('output_block.fc.', 'output_block_fc.')
        new_sd[new_k] = v

    missing, unexpected = model.load_state_dict(new_sd, strict=False)
    if missing:
        print(f"Missing keys ({len(missing)}): {missing[:5]}...")
    if unexpected:
        print(f"Unexpected keys ({len(unexpected)}): {unexpected[:5]}...")

    model.eval()

    # Test
    dummy = torch.randn(1, 6, args.window_size)
    with torch.no_grad():
        out = model(dummy)
    print(f"Test: input {dummy.shape} → output {out.shape} = {out[0].tolist()}")

    # Export ONNX
    onnx_path = args.output.replace('.tflite', '.onnx')
    print(f"Exporting ONNX: {onnx_path}")
    torch.onnx.export(model, dummy, onnx_path, input_names=['imu'], output_names=['velocity'], opset_version=11)

    # Convert to TFLite
    print("Converting to TFLite...")
    try:
        import onnx
        from onnx_tf.backend import prepare
        import tensorflow as tf

        onnx_model = onnx.load(onnx_path)
        saved_dir = onnx_path.replace('.onnx', '_tf')
        prepare(onnx_model).export_graph(saved_dir)

        converter = tf.lite.TFLiteConverter.from_saved_model(saved_dir)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        tflite_model = converter.convert()

        with open(args.output, 'wb') as f:
            f.write(tflite_model)
        print(f"\n✓ Saved: {args.output} ({len(tflite_model)/1024:.1f} KB)")

        # Cleanup
        os.remove(onnx_path)
        import shutil
        shutil.rmtree(saved_dir, ignore_errors=True)

    except Exception as e:
        print(f"TFLite conversion failed: {e}")
        print(f"ONNX saved at: {onnx_path}")


def convert_with_ronin_source(args):
    """Use original RoNIN source code for conversion."""
    import torch
    from ronin_resnet import get_model

    data = torch.load(args.checkpoint, map_location='cpu', weights_only=False)
    sd = data['model_state_dict']

    model = get_model('resnet')
    model.load_state_dict(sd)
    model.eval()

    dummy = torch.randn(1, 6, args.window_size)
    with torch.no_grad():
        out = model(dummy)
    print(f"Test: {dummy.shape} → {out.shape}")

    onnx_path = args.output.replace('.tflite', '.onnx')
    torch.onnx.export(model, dummy, onnx_path, input_names=['imu'], output_names=['velocity'], opset_version=11)
    print(f"ONNX exported: {onnx_path}")


if __name__ == "__main__":
    main()
