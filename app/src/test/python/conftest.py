import sys
from pathlib import Path

# The engine lives where Chaquopy packages it into the app; the tests import it from there.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'main' / 'python'))
