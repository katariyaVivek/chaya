"""Starts Chaya's engine from the folder Chaya for Windows installs it in.

The app runs `python -I run.py <command> ...`. Isolated mode (-I) keeps a Python the person installed, and its
settings, out of the way; this file then puts packages/ (yt-dlp, gallery-dl, chaya_engine) first on the path.
"""
import os
import runpy
import sys

sys.dont_write_bytecode = True  # the packages are compiled when the engine is prepared
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'packages'))
runpy.run_module('chaya_engine.cli', run_name='__main__', alter_sys=True)
