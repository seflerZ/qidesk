export ANDROID_SDK=${HOME}/Android/Sdk
echo "USER_UID=$(id -u)" > docker/.env
echo "USER_GID=$(id -g)" >> docker/.env
echo "ANDROID_SDK=${ANDROID_SDK}" >> docker/.env
echo "CURRENT_WORKING_DIR=$(pwd)" >> docker/.env
docker-compose -f docker/docker-compose.yml up
