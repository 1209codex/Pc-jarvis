from setuptools import setup, find_packages

setup(
    name="jarvis-linux",
    version="1.0.0",
    description="J.A.R.V.I.S. — Autonomous Linux AI Super-Agent & Voice Assistant",
    author="Google DeepMind Advanced Agentic Coding",
    packages=find_packages(),
    install_requires=[
        "PySide6>=6.6.0",
        "sounddevice>=0.4.6",
        "numpy>=1.24.0",
        "scipy>=1.10.0",
        "requests>=2.31.0",
        "qasync>=0.28.0"
    ],
    entry_points={
        "console_scripts": [
            "jarvis=jarvis.main:main",
        ],
    },
    python_requires=">=3.10",
)
