These response bodies were captured from the legacy 0.6.0.2 Docker baseline replay
using the fixtures in `.scratch/scheme3-migration/baseline/fixtures/`. The HTTPS
integration test compares the complete JSON structure and stable values with the
new Driver. It normalizes generated mission and process IDs, log content, timestamps,
and runtime-dependent metrics; histogram arrays are checked for their original size.
The captured logs and metrics use only the baseline Mock provider data.
