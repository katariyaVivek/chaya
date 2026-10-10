import importlib
import importlib.resources
import json
import sys
import zipfile

import pytest

from chaya_engine import paths


@pytest.fixture(autouse=True)
def restore_imports():
    """Each test changes what is imported; put back the real packages for the other tests."""
    modules = dict(sys.modules)
    path = list(sys.path)
    yield
    paths.drop()
    sys.modules.clear()
    sys.modules.update(modules)
    sys.path[:] = path
    importlib.invalidate_caches()


def wheel(tmp_path, name, files):
    """A wheel as PyPI serves it: a zip of the package's files, plus its metadata folder."""
    path = tmp_path / name
    with zipfile.ZipFile(path, 'w') as out:
        for file_name, text in files.items():
            out.writestr(file_name, text)
        out.writestr(name.split('-')[0] + '-0.dist-info/METADATA', 'Name: x\nVersion: 0\n')
    return path


def fake_yt_dlp(tmp_path, version, name='yt_dlp-new.whl'):
    return wheel(tmp_path, name, {
        'yt_dlp/__init__.py': '',
        'yt_dlp/version.py': f'__version__ = {version!r}\n',
    })


def test_the_files_in_use_are_imported_before_the_copy_in_the_app(tmp_path):
    import yt_dlp.version
    bundled = yt_dlp.version.__version__

    paths.use([fake_yt_dlp(tmp_path, '2099.1.1')])

    assert importlib.import_module('yt_dlp.version').__version__ == '2099.1.1'
    assert bundled != '2099.1.1'


def test_files_go_on_sys_path_first_and_in_the_order_given(tmp_path):
    first = fake_yt_dlp(tmp_path, '2099.1.1', 'yt_dlp-a.whl')
    second = wheel(tmp_path, 'gallery_dl-b.whl', {'gallery_dl/__init__.py': ''})

    paths.use([first, second])

    assert sys.path[:2] == [str(first), str(second)]
    assert paths.active() == [str(first), str(second)]


def test_use_forgets_engine_modules_already_imported_but_keeps_itself(tmp_path):
    import chaya_engine.extract  # noqa: F401  (imports the app's yt_dlp)
    assert 'yt_dlp' in sys.modules

    paths.use([fake_yt_dlp(tmp_path, '2099.1.1')])

    assert 'yt_dlp' not in sys.modules
    assert 'chaya_engine.extract' not in sys.modules
    assert 'chaya_engine.paths' in sys.modules
    assert importlib.import_module('yt_dlp.version').__version__ == '2099.1.1'


def test_a_set_that_fails_its_check_is_dropped_and_the_copy_in_the_app_comes_back(tmp_path):
    import yt_dlp.version
    bundled = yt_dlp.version.__version__
    broken = wheel(tmp_path, 'yt_dlp-broken.whl', {'yt_dlp/__init__.py': 'raise ImportError("broken on purpose")\n'})

    paths.use([broken])
    with pytest.raises(ImportError, match='broken on purpose'):
        importlib.import_module('yt_dlp')
    paths.drop()

    assert str(broken) not in sys.path
    assert paths.active() == []
    assert importlib.import_module('yt_dlp.version').__version__ == bundled
    # The engine itself works again: its status imports yt-dlp, the solver and gallery-dl.
    from chaya_engine import selftest
    status = json.loads(selftest.status())
    assert status['yt_dlp'] == bundled
    assert status['files'] == []


def test_a_compiled_wheel_imports_without_its_sources_and_keeps_its_other_files(tmp_path):
    source = wheel(tmp_path, 'yt_dlp_ejs-new.whl', {
        'yt_dlp_ejs/__init__.py': 'version = "9.9.9"\n',
        'yt_dlp_ejs/yt/__init__.py': '',
        'yt_dlp_ejs/yt/solver/__init__.py': (
            'import importlib.resources\n'
            'def core():\n'
            '    return (importlib.resources.files(__name__) / "core.min.js").read_text()\n'
        ),
        'yt_dlp_ejs/yt/solver/core.min.js': 'var solver = 1;',
    })
    compiled = tmp_path / 'yt_dlp_ejs-new.zip'

    paths.compile_wheel(str(source), str(compiled))

    names = zipfile.ZipFile(compiled).namelist()
    assert 'yt_dlp_ejs/__init__.pyc' in names
    assert not any(name.endswith('.py') for name in names)
    assert not any('.dist-info' in name for name in names)
    paths.use([compiled])
    assert importlib.import_module('yt_dlp_ejs').version == '9.9.9'
    assert importlib.import_module('yt_dlp_ejs.yt.solver').core() == 'var solver = 1;'
    assert importlib.import_module('yt_dlp_ejs').__file__.endswith('.pyc')


def test_the_real_yt_dlp_wheel_works_once_compiled(tmp_path):
    """Builds a wheel from the installed yt-dlp and yt-dlp-ejs (the pinned ones), compiles both and runs the
    engine's own self-test on them, as EngineSets does with a new set."""
    import yt_dlp
    import yt_dlp_ejs
    from pathlib import Path

    compiled = []
    for module in (yt_dlp, yt_dlp_ejs):
        package = Path(module.__file__).parent
        source = tmp_path / f'{module.__name__}.whl'
        with zipfile.ZipFile(source, 'w') as out:
            for file in package.rglob('*'):
                if file.is_file() and '__pycache__' not in file.parts:
                    out.write(file, str(file.relative_to(package.parent)))
        target = tmp_path / f'{module.__name__}.zip'
        paths.compile_wheel(str(source), str(target))
        compiled.append(target)

    paths.use(compiled)
    from chaya_engine import selftest
    status = json.loads(selftest.status())

    assert status['provider_registered'] is True
    assert status['files'] == [str(f) for f in compiled]
    assert importlib.import_module('yt_dlp').__file__.startswith(str(compiled[0]))


def test_the_version_of_a_library_in_the_app_is_reported():
    import requests

    assert paths.installed_version('requests') == requests.__version__
    assert paths.installed_version('no-such-library-here') is None
