#!/usr/bin/env python3
"""用官方 Android SDK 构建个人安装版，不依赖 Gradle 或第三方应用运行时。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parent


def load_local_config():
    """读取被 Git 忽略的本机路径配置，避免源码依赖原工作区布局。"""
    path = PROJECT / "build.local.json"
    if not path.exists():
        return {}
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SystemExit("无法读取 build.local.json：" + str(error))
    allowed = {"build_tools", "android_jar", "build_dir", "keystore"}
    if not isinstance(config, dict) or set(config) - allowed:
        raise SystemExit("build.local.json 只支持 build_tools、android_jar、build_dir、keystore。")
    if any(not isinstance(value, str) or not value.strip() for value in config.values()):
        raise SystemExit("build.local.json 中的路径必须为非空字符串。")
    return config


LOCAL_CONFIG = load_local_config()


def configured_path(env_name, config_name, default):
    """按环境变量、本机配置、默认值的顺序选择路径，相对路径基于项目根目录。"""
    value = os.environ.get(env_name) or LOCAL_CONFIG.get(config_name) or default
    path = Path(value).expanduser()
    return (path if path.is_absolute() else PROJECT / path).resolve()


SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or Path.home() / "Library/Android/sdk").expanduser()
TOOLS = configured_path("ZEN_BUILD_TOOLS", "build_tools", SDK / "build-tools/36.0.0")
ANDROID_JAR = configured_path("ZEN_ANDROID_JAR", "android_jar", SDK / "platforms/android-36/android.jar")
BUILD = configured_path("ZEN_BUILD_DIR", "build_dir", PROJECT / "build")
KEYSTORE = configured_path("ZEN_KEYSTORE", "keystore", PROJECT / ".signing/personal.jks")
VERSION = ET.parse(PROJECT / "AndroidManifest.xml").getroot().get(
    "{http://schemas.android.com/apk/res/android}versionName")
OUTPUT = PROJECT / "dist" / ("zen-alarm-v" + VERSION + ".apk")


def run(*args):
    """运行明确的构建命令，任何失败均立即停止。"""
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    """运行时间边界测试、资源编译、DEX 转换、APK 对齐和签名验证。"""
    if not ANDROID_JAR.is_file():
        raise SystemExit("缺少 Android 36 平台包，请设置 ZEN_ANDROID_JAR。")
    BUILD.mkdir(parents=True, exist_ok=True)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    # 清理本项目生成目录，避免删改类后旧的字节码混入新版本。
    for folder in ["classes", "generated", "dex"]:
        shutil.rmtree(BUILD / folder, ignore_errors=True)
    for name in ["aapt2", "d8", "zipalign", "apksigner"]:
        tool = TOOLS / name
        if not tool.exists():
            raise SystemExit("缺少 Android Build Tools 36.0.0：" + str(tool))
        tool.chmod(tool.stat().st_mode | 0o111)
    tests = BUILD / "tests"
    tests.mkdir(exist_ok=True)
    run("javac", "--release", "8", "-encoding", "UTF-8", "-d", tests,
        PROJECT / "src/com/hujia/zenalarm/AlarmTime.java", PROJECT / "tests/AlarmTimeTest.java")
    run("java", "-cp", tests, "com.hujia.zenalarm.AlarmTimeTest")
    generated = BUILD / "generated"
    generated.mkdir(exist_ok=True)
    resources = BUILD / "resources.zip"
    base = BUILD / "resources.apk"
    run(TOOLS / "aapt2", "compile", "--dir", PROJECT / "res", "-o", resources)
    run(TOOLS / "aapt2", "link", "-o", base, "-I", ANDROID_JAR,
        "--manifest", PROJECT / "AndroidManifest.xml", "--java", generated, resources)
    classes = BUILD / "classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((PROJECT / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run("javac", "--release", "8", "-encoding", "UTF-8", "-classpath", ANDROID_JAR,
        "-d", classes, *sources)
    jar = BUILD / "classes.jar"
    with zipfile.ZipFile(jar, "w") as z:
        for path in sorted(classes.rglob("*.class")):
            z.write(path, path.relative_to(classes))
    dex = BUILD / "dex"
    dex.mkdir(exist_ok=True)
    run(TOOLS / "d8", "--min-api", "31", "--lib", ANDROID_JAR, "--output", dex, jar)
    unsigned = BUILD / "unsigned.apk"
    shutil.copyfile(base, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(dex.glob("*.dex")):
            z.write(path, path.name)
    aligned = BUILD / "aligned.apk"
    run(TOOLS / "zipalign", "-f", "4", unsigned, aligned)
    KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
    if not KEYSTORE.exists():
        # 与常规本地调试签名一样使用固定口令；私钥存入被忽略的本机目录，须保留用于升级。
        run("keytool", "-genkeypair", "-keystore", KEYSTORE, "-storepass", "android",
            "-keypass", "android", "-alias", "zen-personal", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "10000", "-dname", "CN=Zen Alarm Personal")
    run(TOOLS / "apksigner", "sign", "--ks", KEYSTORE, "--ks-key-alias", "zen-personal",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", OUTPUT, aligned)
    run(TOOLS / "apksigner", "verify", "--verbose", OUTPUT)
    print("APK:", OUTPUT)
    print("SHA256:", hashlib.sha256(OUTPUT.read_bytes()).hexdigest())
    print("SIZE:", OUTPUT.stat().st_size)


if __name__ == "__main__":
    main()
