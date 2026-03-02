# NTFS Validation Pipeline

This folder contains the end-to-end NTFS validation flow:

1. Android instrumentation stress workload (create/open/add/overwrite/extract/delete/reopen).
2. Artifact pull (`ntfs_stress_report.json` + container image).
3. Optional Windows `chkdsk` pass against the produced container.

## Scripts

- `run_android_ntfs_validation.sh`
  - Installs debug + androidTest APKs (unless `--skip-install`).
  - Runs `NtfsIntegrityValidationTest` with configurable stress parameters.
  - Pulls artifacts from app internal storage using `adb exec-out run-as`.

- `Invoke-NtfsChkdskValidation.ps1`
  - Mounts the container via VeraCrypt CLI (or `-SkipMount`).
  - Runs `chkdsk` and writes a machine-readable JSON result.
  - Dismounts the volume in `finally`.

- `run_ntfs_validation_pipeline.sh`
  - Wrapper that runs Android validation then auto-runs PowerShell stage when available.

## Typical usage

```bash
scripts/validation/run_ntfs_validation_pipeline.sh \
  --password "replace-this" \
  --iterations 240 \
  --size-mb 256
```

Artifacts are written to `out/ntfs-validation/<timestamp>/`.

## Instrumentation args

- `vcPassword`
- `containerName`
- `containerSizeMb`
- `iterations`
- `minPayloadBytes`
- `maxPayloadBytes`
- `verifyEvery`
- `overwriteEvery`
- `deletePercent`
- `finalVerifySampleSize`
- `seed` (optional)
