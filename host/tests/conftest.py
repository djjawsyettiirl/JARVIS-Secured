import os
import tempfile
from pathlib import Path


# Set the data location before application modules create their Store instances.
os.environ["JARVIS_DATA_DIR"] = str(Path(tempfile.gettempdir()) / "jarvis-tests")
