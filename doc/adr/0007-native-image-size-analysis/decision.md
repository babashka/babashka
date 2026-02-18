# ADR 0007: Native Image Size Analysis

## How to measure native image size impact of changes

### 1. Enable JSON report in `script/compile`

Uncomment (or add) the `-H:BuildOutputJSONFile` flag:

```bash
"-H:BuildOutputJSONFile=report.json"
```

### 2. Build baseline on master

```bash
git stash  # if needed
git checkout master
# edit script/compile: "-H:BuildOutputJSONFile=report-before.json"
script/uberjar && script/compile
mv report-before.json tmp/
```

### 3. Build with changes

```bash
git checkout <your-branch>
# edit script/compile: "-H:BuildOutputJSONFile=report-after.json"
script/uberjar && script/compile
mv report-after.json tmp/
```

### 4. Compare reports

```bash
bb doc/adr/0007-native-image-size-analysis/compare-reports.bb tmp/report-before.json tmp/report-after.json
```

The JSON contains:

- `image_details.total_bytes` — total binary size
- `image_details.code_area.bytes` — compiled native code
- `image_details.image_heap.bytes` — heap snapshot
- `image_details.image_heap.objects.count` — heap object count
- `analysis_results.types` — total, reachable, reflection
- `analysis_results.methods` — total, reachable, reflection
- `analysis_results.fields` — total, reachable, reflection

Key metrics to watch: `total_bytes`, `code_area.bytes`, `methods.reachable`, `methods.reflection`.

### 5. Don't forget to revert `script/compile`

Remove or re-comment the `-H:BuildOutputJSONFile` flag before committing.

## Lessons learned

### Bare `:all` class entries are expensive

Classes listed under `:all` in `classes.clj` get `allPublicMethods`, `allPublicConstructors`, etc. in reflection config. This makes all their methods reachable, pulling in transitive types.

Example: `java.util.stream.Collectors` under `:all` added 44 methods, 86 types, and ~49KB to the binary. Restricting to `{:methods [{:name "toList"}]}` reduced the impact to +32 bytes.

### Explicit `:methods` entries are nearly free

Adding a class with specific methods listed barely affects image size. The reflection metadata is registered but only listed methods become reachable.

### Where the size goes

- Code area: compiled native code for reachable methods (~175 bytes/method average)
- Image heap: object snapshots (often unchanged for small additions)
- Reflection metadata itself is cheap; reachability of methods is what costs
