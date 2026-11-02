#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo 'Usage: ./run-isolated.sh /absolute/path/to/application.jar [new-results-directory]' >&2
    exit 2
fi
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app_dir=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)
app_jar="$app_dir/$(basename -- "$1")"
[ -f "$app_jar" ] || { echo 'Application JAR not found' >&2; exit 2; }
results_dir=${2:-"$project_dir/target/run-$(date +%Y%m%d-%H%M%S)-$$"}
if [ -e "$results_dir" ]; then
    echo 'Results directory must be new; existing results will not be overwritten' >&2
    exit 2
fi
command -v docker >/dev/null || { echo 'Docker is required' >&2; exit 2; }
docker info >/dev/null
docker build --tag nordcodes-aqa-tests:local "$project_dir"
app_volume=$(docker volume create)
results_volume=$(docker volume create)
copy_container=''
container_id=''
cleanup() {
    if [ -n "$copy_container" ]; then docker rm -f "$copy_container" >/dev/null 2>&1 || true; fi
    if [ -n "$container_id" ]; then docker rm -f "$container_id" >/dev/null 2>&1 || true; fi
    docker volume rm "$app_volume" "$results_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
copy_container=$(docker create --network none --mount "type=volume,src=$app_volume,dst=/app" \
    --entrypoint true nordcodes-aqa-tests:local)
docker cp "$app_jar" "$copy_container:/app/application.jar"
docker rm "$copy_container" >/dev/null
copy_container=''
container_id=$(docker create --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges:true --pids-limit 256 --memory 1536m --cpus 2 \
    --tmpfs /tmp:rw,nosuid,nodev,size=256m,mode=1777 \
    --mount "type=volume,src=$app_volume,dst=/app,readonly" \
    --mount "type=volume,src=$results_volume,dst=/project/target" \
    --tmpfs /home/tester:rw,nosuid,nodev,size=64m,uid=10001,gid=10001 \
    nordcodes-aqa-tests:local)
set +e
docker start --attach "$container_id"
attach_status=$?
set -e
exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$container_id")
mkdir -p "$results_dir"
docker cp "$container_id:/project/target/." "$results_dir/"
printf '\nResults: %s\nContainer exit code: %s\n' "$results_dir" "$exit_code"
if [ "$exit_code" -eq 0 ] && [ "$attach_status" -ne 0 ]; then exit "$attach_status"; fi
exit "$exit_code"
