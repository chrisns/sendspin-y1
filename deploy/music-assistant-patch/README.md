# Music Assistant patch (no fork)

`sitecustomize.py` patches the stock Music Assistant image at start-up: Python imports it automatically when it is on
`PYTHONPATH`. It currently makes the Apple Music provider's "remove from library" work (through Apple's web player
endpoint), deleting only from accounts the requesting user can see. If an upgrade changes the patched code it logs
"local patch ... not applied" and Music Assistant runs unpatched.

Kubernetes (kustomize):

```yaml
# kustomization.yaml
configMapGenerator:
  - name: music-assistant-patches
    files: [sitecustomize.py]
```

```yaml
# in the Music Assistant container
env:
  - {name: PYTHONPATH, value: /patches}
volumeMounts:
  - {name: patches, mountPath: /patches, readOnly: true}
# in the pod
volumes:
  - {name: patches, configMap: {name: music-assistant-patches}}
```

Docker: mount the file at `/patches/sitecustomize.py` and set `PYTHONPATH=/patches`.
Check the log after start-up for `local patch applied`.
