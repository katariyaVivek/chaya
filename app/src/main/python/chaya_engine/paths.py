"""Which copy of yt-dlp, yt-dlp-ejs and gallery-dl the engine imports.

The app carries its own copy. Newer ones fetched from PyPI (`EngineUpdater` in Kotlin) are kept as compiled
zips, and `EngineSets` puts the chosen ones at the front of `sys.path` with `use` before anything imports
yt_dlp. Imported modules cannot be swapped safely while they run, so this happens when the engine starts;
`drop` exists for a set that fails its first check, before any lookup has used it.

Nothing here imports the engine's packages, so it is safe to load first.
"""
import importlib
import importlib.util
import marshal
import sys
import zipfile
import zipimport

# The top-level modules that come from the engine's packages, and the modules of ours that import them.
_PACKAGES = ('yt_dlp', 'yt_dlp_ejs', 'gallery_dl')
_KEEP = ('chaya_engine', 'chaya_engine.paths')

_active = []


def use(files):
    """Puts [files] (compiled zips, or wheels) at the front of `sys.path`, in the order given, and forgets
    every engine module already imported, so the next import comes from them."""
    drop()
    for path in reversed([str(f) for f in files]):
        sys.path.insert(0, path)
    _active[:] = [str(f) for f in files]
    importlib.invalidate_caches()


def drop():
    """Takes the files `use` added off `sys.path` and forgets the engine's modules: the next import uses the
    copy in the app."""
    for path in _active:
        while path in sys.path:
            sys.path.remove(path)
        sys.path_importer_cache.pop(path, None)
        zipimport._zip_directory_cache.pop(path, None)  # noqa: SLF001 - a replaced file must be read again
    _active.clear()
    for name in list(sys.modules):
        if _is_engine_module(name):
            del sys.modules[name]
            # `from . import x` takes a submodule bound on its package (chaya_engine, which stays) without importing
            # it again, which would leave the solver registered with the yt-dlp just forgotten.
            parent, _, child = name.rpartition('.')
            if parent in sys.modules and not _is_engine_module(parent) and hasattr(sys.modules[parent], child):
                delattr(sys.modules[parent], child)
    importlib.invalidate_caches()


def active():
    """The files in use, in order; empty while the copy in the app is used."""
    return list(_active)


def _is_engine_module(name):
    if name.split('.')[0] in _PACKAGES:
        return True
    return name.startswith('chaya_engine.') and name not in _KEEP


def compile_wheel(wheel, out):
    """Writes [out], a zip holding [wheel]'s modules compiled for this Python, so they load as fast as the
    app's own copy (a wheel's sources would be compiled again on every start). Other files, such as
    yt-dlp-ejs's scripts, are copied as they are. The wheel's metadata folder is left out."""
    with zipfile.ZipFile(wheel) as source, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            name = info.filename
            if info.is_dir() or name.split('/')[0].endswith(('.dist-info', '.data')):
                continue
            data = source.read(info)
            if name.endswith('.py'):
                code = compile(data, name, 'exec', dont_inherit=True, optimize=0)
                # A .pyc with no source beside it: a timestamp header of zeros, which is never checked.
                target.writestr(name + 'c', importlib.util.MAGIC_NUMBER + bytes(12) + marshal.dumps(code))
            else:
                target.writestr(name, data)


def installed_version(name):
    """The version of the library [name] (as PyPI names it) inside the app, or None when it has none."""
    from importlib import metadata

    try:
        return metadata.version(name)
    except Exception:  # noqa: BLE001 - not installed, or installed without metadata
        pass
    module = name.replace('-', '_').lower()
    try:
        return getattr(importlib.import_module(module), '__version__', None)
    except ImportError:
        return None
