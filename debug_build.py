
import subprocess
import sys

try:
    process = subprocess.run(['gradlew.bat', 'assembleRelease', '--info'], cwd='android', check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=True)
    with open('debug_output.txt', 'w') as f:
        f.write(process.stdout)
except subprocess.CalledProcessError as e:
    with open('debug_output.txt', 'w') as f:
        f.write("STDOUT:\n" + e.stdout + "\nSTDERR:\n" + e.stderr)
