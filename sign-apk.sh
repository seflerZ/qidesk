#!/bin/bash

# APK 打包、对齐和签名脚本
# 支持 freeDebug 和 gplayDebug 两个 flavor

set -e

# 配置
PROJECT_DIR="/home/sefler/remote-desktop-clients"
APP_MODULE="aRDP-app"
BUILD_TOOLS_VERSION="30.0.2"
ANDROID_SDK="${HOME}/Android/Sdk"
KEYSTORE="${HOME}/.android/debug.keystore"
KEY_PASS="android"
OUTPUT_DIR="${HOME}/qidesk"

# Build tools 路径
BUILD_TOOLS="${ANDROID_SDK}/build-tools/${BUILD_TOOLS_VERSION}"
ZIPALIGN="${BUILD_TOOLS}/zipalign"
APKSIGNER="${BUILD_TOOLS}/apksigner"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查必要工具
check_tools() {
    if [ ! -f "$ZIPALIGN" ]; then
        log_error "zipalign not found at $ZIPALIGN"
        exit 1
    fi
    if [ ! -f "$APKSIGNER" ]; then
        log_error "apksigner not found at $APKSIGNER"
        exit 1
    fi
    if [ ! -f "$KEYSTORE" ]; then
        log_error "Keystore not found at $KEYSTORE"
        exit 1
    fi
}

# 构建指定 flavor 的 APK
build_apk() {
    local flavor=$1
    local build_type="Debug"
    local flavor_lower=$(echo "$flavor" | tr '[:upper:]' '[:lower:]')

    log_info "Building ${flavor_lower}${build_type}..."

    cd "$PROJECT_DIR"

    # 执行 Gradle 构建
    ./gradlew ":${APP_MODULE}:assemble${flavor}${build_type}"

    # APK 路径
    local apk_dir="${PROJECT_DIR}/${APP_MODULE}/build/outputs/apk/${flavor_lower}/debug"
    local apk_name="${APP_MODULE}-${flavor_lower}-debug.apk"
    local original_apk="${apk_dir}/${apk_name}"

    if [ ! -f "$original_apk" ]; then
        log_error "APK not found: $original_apk"
        exit 1
    fi

    log_info "APK built: $original_apk"

    # 创建对齐后的 APK 文件名
    local aligned_apk="${apk_dir}/${APP_MODULE}-${flavor_lower}-16KB.apk"

    # 执行 zipalign (转换为 16KB 对齐)
    log_info "Running zipalign (16KB alignment)..."
    "$ZIPALIGN" -v -f -p 16384 "$original_apk" "$aligned_apk"

    # 签名
    log_info "Signing APK..."
    local final_apk="${OUTPUT_DIR}/${APP_MODULE}-${flavor_lower}.apk"

    # 确保输出目录存在
    mkdir -p "$OUTPUT_DIR"

    "$APKSIGNER" sign \
        -ks "$KEYSTORE" \
        --ks-pass pass:"$KEY_PASS" \
        --key-pass pass:"$KEY_PASS" \
        --out "$final_apk" \
        "$aligned_apk"

    log_info "Signed APK: $final_apk"
    log_info "${flavor_lower}${build_type} completed successfully!"
    echo ""
}

# 主函数
main() {
    check_tools

    local flavors=()

    # 解析参数
    if [ $# -eq 0 ]; then
        # 默认构建两个 flavor
        flavors=("gplay" "free")
    else
        for arg in "$@"; do
            case "$arg" in
                gplay|gplayDebug|GplayDebug)
                    flavors+=("gplay")
                    ;;
                free|freeDebug|FreeDebug)
                    flavors+=("free")
                    ;;
                all)
                    flavors=("gplay" "free")
                    ;;
                *)
                    log_error "Unknown flavor: $arg"
                    echo "Usage: $0 [gplay|free|all]"
                    echo "  No arguments: build both flavors"
                    echo "  gplay: build gplayDebug only"
                    echo "  free: build freeDebug only"
                    echo "  all: build both flavors"
                    exit 1
                    ;;
            esac
        done
    fi

    # 去重
    flavors=($(echo "${flavors[@]}" | tr ' ' '\n' | sort -u | tr '\n' ' '))

    log_info "Will build flavors: ${flavors[*]}"
    echo ""

    for flavor in "${flavors[@]}"; do
        build_apk "$flavor"
    done

    log_info "All builds completed!"
}

main "$@"