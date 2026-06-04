"""
Upload the converted RoNIN TFLite model to Azure Blob Storage (models container).

Usage:
    python upload_ronin.py ronin_model.tflite
"""

import os
import sys
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), '..', '..', 'findmycar-ml', '.env'))


def main():
    if len(sys.argv) < 2:
        print("Usage: python upload_ronin.py <path-to-ronin_model.tflite>")
        sys.exit(1)

    tflite_path = sys.argv[1]
    if not os.path.exists(tflite_path):
        print(f"ERROR: File not found: {tflite_path}")
        sys.exit(1)

    conn_str = os.getenv("AZURE_STORAGE_CONNECTION_STRING")
    if not conn_str:
        print("ERROR: AZURE_STORAGE_CONNECTION_STRING not set")
        sys.exit(1)

    from azure.storage.blob import ContainerClient

    client = ContainerClient.from_connection_string(conn_str, "models")

    # Upload model
    blob_name = "ronin_model.tflite"
    with open(tflite_path, "rb") as f:
        client.upload_blob(blob_name, f, overwrite=True, metadata={"version": "1.0"})
    size_kb = os.path.getsize(tflite_path) / 1024
    print(f"Uploaded: {blob_name} ({size_kb:.1f} KB)")

    # Upload config
    import json
    config = {
        "model_type": "ronin_resnet",
        "input_channels": 6,
        "input_window_size": 200,
        "input_sample_rate_hz": 200,
        "output_size": 2,
        "output_format": "velocity_ms",
        "output_description": ["velocity_north_ms", "velocity_east_ms"],
        "inference_interval_ms": 200
    }
    config_json = json.dumps(config, indent=2)
    client.upload_blob("ronin_config.json", config_json, overwrite=True, metadata={"version": "1.0"})
    print(f"Uploaded: ronin_config.json")

    print("\nDone. Models container now contains:")
    for blob in client.list_blobs():
        print(f"  {blob.name} ({blob.size} bytes)")


if __name__ == "__main__":
    main()
