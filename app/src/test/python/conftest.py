import sys
import types
from pathlib import Path

import pytest

# The engine lives where Chaquopy packages it into the app; the tests import it from there.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'main' / 'python'))


@pytest.fixture
def fake_java(monkeypatch):
    """Stands in for Chaquopy's `java` module, which only exists inside the app.

    Call the fixture with a {java class name: stand-in} mapping to install it.
    """
    def install(classes):
        module = types.ModuleType('java')
        module.jclass = lambda name: classes[name]
        monkeypatch.setitem(sys.modules, 'java', module)

    return install
