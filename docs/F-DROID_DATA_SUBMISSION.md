# F-Droid Data Repo Submission

This repo now includes an `fdroiddata`-ready metadata file at:

- `fdroiddata/metadata/org.cyberphone.yml`

Use it to open a merge request against the official F-Droid Data repo:

- `https://gitlab.com/fdroid/fdroiddata`

## Fast Submission Flow

1. Fork `fdroid/fdroiddata` on GitLab.
2. Clone your fork.
3. Copy `fdroiddata/metadata/org.cyberphone.yml` from this repo into your fork at `metadata/org.cyberphone.yml`.
4. Commit and push.
5. Open a merge request from your fork to `fdroid/fdroiddata`.

## Example Commands

```bash
git clone https://gitlab.com/<your-gitlab-user>/fdroiddata.git
cd fdroiddata
git checkout -b add-org-cyberphone
cp /path/to/Cyber-Phone/fdroiddata/metadata/org.cyberphone.yml metadata/org.cyberphone.yml
git add metadata/org.cyberphone.yml
git commit -m "Add metadata for org.cyberphone (Cyber Phone)"
git push -u origin add-org-cyberphone
```

Then open the MR in GitLab and link your existing RFP issue.

## Notes

- Keep `Builds[0].commit` pinned to the exact commit you want F-Droid to build.
- Keep `CurrentVersion` and `CurrentVersionCode` aligned with `gradle.properties`.
- For each release, append a new `Builds` block and update current version fields.
