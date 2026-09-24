# Python File Handling: Complete Practical Guide

File handling in Python is straightforward, but production-quality file I/O requires more than knowing `open()`. You need to think about encoding, resource management, exceptions, paths, atomicity, permissions, and data formats.

---

## 1. Opening Files with Different Modes

Python's main file-opening function is:

```python
open(file, mode="r", encoding="utf-8")
```

The most common modes are:

| Mode | Meaning                      |
| ---- | ---------------------------- |
| `r`  | Read                         |
| `w`  | Write; creates or overwrites |
| `a`  | Append                       |
| `rb` | Read binary                  |
| `wb` | Write binary                 |
| `r+` | Read and write               |
| `w+` | Write and read; truncates    |
| `a+` | Append and read              |

### 1.1 Read mode: `r`

Use this when you want to read an existing text file.

```python
with open("example.txt", "r", encoding="utf-8") as file:
    content = file.read()

print(content)
```

Important:

* The file must already exist.
* If it doesn't, Python raises `FileNotFoundError`.

### 1.2 Write mode: `w`

```python
with open("example.txt", "w", encoding="utf-8") as file:
    file.write("Hello, Python!\n")
    file.write("This is a new file.")
```

`w` has an important behavior beginners often miss:

```text
Existing file
     ↓
open(..., "w")
     ↓
Existing contents are erased
```

It creates the file if it does not exist.

### 1.3 Append mode: `a`

Use append when you want to add data to the end.

```python
with open("log.txt", "a", encoding="utf-8") as file:
    file.write("New log entry\n")
```

Existing data is preserved.

### 1.4 Binary read: `rb`

Binary mode is used for images, PDFs, ZIP files, executables, etc.

```python
with open("image.png", "rb") as file:
    data = file.read()

print(type(data))
```

Output:

```text
<class 'bytes'>
```

### 1.5 Binary write: `wb`

```python
data = b"Hello binary data"

with open("data.bin", "wb") as file:
    file.write(data)
```

Do not try to write a normal `str` directly in binary mode:

```python
# Wrong
with open("data.bin", "wb") as file:
    file.write("hello")
```

Instead:

```python
with open("data.bin", "wb") as file:
    file.write("hello".encode("utf-8"))
```

### 1.6 Read/write mode: `r+`

`r+` allows both reading and writing.

```python
with open("example.txt", "r+", encoding="utf-8") as file:
    content = file.read()
    print(content)

    file.write("\nAdded later.")
```

The file must already exist.

Be careful with `r+`: writing occurs at the current file position.

---

# 2. Why `with` Is Important

The preferred way to work with files is:

```python
with open("example.txt", "r", encoding="utf-8") as file:
    content = file.read()
```

Rather than:

```python
file = open("example.txt", "r", encoding="utf-8")
content = file.read()
file.close()
```

### What does `with` do?

A file is an operating-system resource. It needs to be closed after use.

The `with` statement creates a context manager that guarantees cleanup.

Conceptually:

```python
file = open(...)
try:
    ...
finally:
    file.close()
```

So even if an exception occurs:

```python
with open("example.txt", encoding="utf-8") as file:
    raise RuntimeError("Something went wrong")
```

Python still closes the file.

### Why this matters

Without proper cleanup, long-running applications can accumulate open file descriptors.

This is especially important in:

* servers
* background workers
* Android/Linux tooling
* scripts processing thousands of files
* logging systems
* database applications

### Best practice

Use:

```python
with open(...) as file:
    ...
```

almost every time.

---

# 3. Reading File Contents

Python provides several ways to read text.

## 3.1 `read()`

Reads the entire file.

```python
with open("example.txt", encoding="utf-8") as file:
    content = file.read()

print(content)
```

You can specify a number of characters:

```python
with open("example.txt", encoding="utf-8") as file:
    content = file.read(100)

print(content)
```

This reads approximately the next 100 characters.

### When to use

Good for small files.

Avoid loading enormous files entirely into memory.

---

## 3.2 `readline()`

Reads one line at a time.

```python
with open("example.txt", encoding="utf-8") as file:
    line = file.readline()
    print(line)
```

Multiple calls move through the file:

```python
with open("example.txt", encoding="utf-8") as file:
    print(file.readline())
    print(file.readline())
    print(file.readline())
```

---

## 3.3 `readlines()`

Reads all lines into a list.

```python
with open("example.txt", encoding="utf-8") as file:
    lines = file.readlines()

print(lines)
```

Example:

```text
["Line 1\n", "Line 2\n", "Line 3\n"]
```

Again, the entire file is held in memory.

---

## 3.4 Iterating over a file

Usually the best option for line-oriented processing:

```python
with open("large.log", encoding="utf-8") as file:
    for line in file:
        print(line.strip())
```

Python streams lines rather than requiring you to load the entire file into a list.

For large files:

```python
with open("huge.log", encoding="utf-8") as file:
    for line in file:
        if "ERROR" in line:
            print(line.strip())
```

This is much more memory-efficient.

---

# 4. Writing to Files

## 4.1 `write()`

`write()` writes a string.

```python
with open("output.txt", "w", encoding="utf-8") as file:
    file.write("Hello\n")
    file.write("Python file handling\n")
```

It returns the number of characters written:

```python
with open("output.txt", "w", encoding="utf-8") as file:
    count = file.write("Hello")

print(count)
```

---

## 4.2 `writelines()`

`writelines()` writes an iterable of strings.

```python
lines = [
    "First line\n",
    "Second line\n",
    "Third line\n"
]

with open("output.txt", "w", encoding="utf-8") as file:
    file.writelines(lines)
```

Important beginner mistake:

```python
lines = ["First", "Second", "Third"]
```

`writelines()` does **not** automatically add newlines.

You would get:

```text
FirstSecondThird
```

Instead:

```python
lines = ["First\n", "Second\n", "Third\n"]
```

---

# 5. String Encoding

Text on disk is represented as bytes. Python needs an encoding to convert between strings and bytes.

Prefer explicitly specifying UTF-8:

```python
with open("data.txt", "r", encoding="utf-8") as file:
    text = file.read()
```

And:

```python
with open("data.txt", "w", encoding="utf-8") as file:
    file.write("नमस्ते Python")
```

UTF-8 handles English, Hindi, emojis, and many other scripts.

### Common mistake

Relying on the platform's default encoding:

```python
open("file.txt", "r")
```

This can behave differently across Windows, Linux, and other environments.

For predictable applications:

```python
open("file.txt", "r", encoding="utf-8")
```

---

# 6. Checking Whether a File Exists

There are two common approaches.

## 6.1 Using `os.path.exists()`

```python
import os

if os.path.exists("example.txt"):
    print("File exists")
else:
    print("File does not exist")
```

You can also check specifically for a file:

```python
if os.path.isfile("example.txt"):
    print("It is a file")
```

And directories:

```python
if os.path.isdir("documents"):
    print("It is a directory")
```

---

## 6.2 Using `pathlib`

For modern Python code, `pathlib` is generally cleaner.

```python
from pathlib import Path

path = Path("example.txt")

if path.exists():
    print("Exists")
```

Specific checks:

```python
if path.is_file():
    print("Regular file")

if path.is_dir():
    print("Directory")
```

### Important caveat

Checking first and then opening is not always necessary.

This:

```python
if path.exists():
    with path.open("r", encoding="utf-8") as file:
        ...
```

has a race condition: another process could delete the file between the check and the open.

Often better:

```python
try:
    with path.open("r", encoding="utf-8") as file:
        content = file.read()
except FileNotFoundError:
    print("File disappeared or does not exist")
```

For production code, **attempting the operation and handling the exception is often more robust than checking first**.

---

# 7. Exception Handling

File operations commonly encounter environmental problems.

## 7.1 `FileNotFoundError`

```python
try:
    with open("missing.txt", encoding="utf-8") as file:
        content = file.read()
except FileNotFoundError:
    print("The file was not found.")
```

---

## 7.2 `PermissionError`

```python
try:
    with open("/protected/file.txt", "w", encoding="utf-8") as file:
        file.write("Hello")
except PermissionError:
    print("You don't have permission to write this file.")
```

---

## 7.3 `IOError` / `OSError`

Modern Python generally uses `OSError` as the broader base class for OS-level I/O failures.

```python
try:
    with open("example.txt", encoding="utf-8") as file:
        data = file.read()
except OSError as exc:
    print(f"I/O error: {exc}")
```

You can handle multiple exceptions:

```python
try:
    with open("example.txt", encoding="utf-8") as file:
        data = file.read()

except FileNotFoundError:
    print("File does not exist.")

except PermissionError:
    print("Permission denied.")

except OSError as exc:
    print(f"Other I/O error: {exc}")
```

### Best practice

Avoid:

```python
except Exception:
    pass
```

That hides real bugs.

Bad:

```python
try:
    with open("data.txt") as file:
        process(file)
except Exception:
    pass
```

You could accidentally hide:

* programming errors
* corrupted data
* invalid assumptions
* permission problems
* encoding failures

Catch the exceptions you actually understand.

---

# 8. Getting File Metadata

You can retrieve metadata using `os.stat()`.

```python
import os

info = os.stat("example.txt")

print("Size:", info.st_size)
print("Modified:", info.st_mtime)
print("Created/changed:", info.st_ctime)
```

### Important platform detail

`st_ctime` does not universally mean "creation time."

On Windows it generally represents creation time, while on Unix-like systems it traditionally represents metadata/status-change time.

So don't blindly label:

```python
st_ctime
```

as:

```text
Creation Time
```

on every operating system.

---

## 8.1 Using `pathlib`

```python
from pathlib import Path

path = Path("example.txt")

print("Size:", path.stat().st_size)
print("Modified:", path.stat().st_mtime)
```

You can convert timestamps:

```python
from datetime import datetime
from pathlib import Path

path = Path("example.txt")

modified = datetime.fromtimestamp(path.stat().st_mtime)

print(modified)
```

---

# 9. Cross-Platform File Paths

Hard-coding separators is a common mistake.

Bad:

```python
path = "C:\\Users\\Bob\\Documents\\file.txt"
```

This is Windows-specific.

Also:

```python
path = "documents/file.txt"
```

may not be appropriate when constructing platform-specific paths programmatically.

## 9.1 `os.path.join()`

```python
import os

path = os.path.join("documents", "reports", "report.txt")

print(path)
```

Python handles the correct separator.

---

## 9.2 `pathlib.Path`

This is generally cleaner.

```python
from pathlib import Path

path = Path("documents") / "reports" / "report.txt"

print(path)
```

This is especially useful because `Path` provides many file operations:

```python
path.exists()
path.is_file()
path.is_dir()
path.stat()
path.read_text()
path.write_text()
```

For modern Python applications, I would generally choose `pathlib` unless an API specifically requires a string path.

---

# 10. Creating Files and Directories

A file is usually created automatically when using `w`:

```python
from pathlib import Path

Path("new.txt").write_text(
    "Hello",
    encoding="utf-8"
)
```

For directories:

```python
from pathlib import Path

directory = Path("data/reports")

directory.mkdir(parents=True, exist_ok=True)
```

`parents=True` creates missing parent directories.

`exist_ok=True` prevents an error when the directory already exists.

---

# 11. Renaming Files

Using `os`:

```python
import os

os.rename("old.txt", "new.txt")
```

Using `pathlib`:

```python
from pathlib import Path

old = Path("old.txt")
new = Path("new.txt")

old.rename(new)
```

For most modern code, `pathlib` is cleaner.

---

# 12. Moving Files

`shutil.move()` is useful for moving files between directories.

```python
import shutil

shutil.move("report.txt", "archive/report.txt")
```

With `pathlib`:

```python
from pathlib import Path
import shutil

source = Path("report.txt")
destination = Path("archive/report.txt")

destination.parent.mkdir(parents=True, exist_ok=True)

shutil.move(source, destination)
```

---

# 13. Copying Files

Use `shutil.copy()`:

```python
import shutil

shutil.copy("source.txt", "backup.txt")
```

To preserve more metadata, use `copy2()`:

```python
shutil.copy2("source.txt", "backup.txt")
```

For directories:

```python
shutil.copytree("source_folder", "backup_folder")
```

Depending on your Python version and requirements, `copytree()` supports an `dirs_exist_ok=True` option for merging into an existing destination.

```python
shutil.copytree(
    "source_folder",
    "backup_folder",
    dirs_exist_ok=True
)
```

---

# 14. Deleting Files

Using `os`:

```python
import os

os.remove("example.txt")
```

Using `pathlib`:

```python
from pathlib import Path

Path("example.txt").unlink()
```

Safer version:

```python
from pathlib import Path

path = Path("example.txt")

if path.exists():
    path.unlink()
```

Again, race conditions mean checking isn't a guarantee. For concurrent applications, exception handling is more robust:

```python
try:
    Path("example.txt").unlink()
except FileNotFoundError:
    pass
```

---

# 15. Working with CSV Files

Do not manually construct CSV files with:

```python
file.write(f"{name},{age}\n")
```

CSV has rules around:

* commas
* quotes
* embedded newlines
* escaping

Use Python's `csv` module.

## 15.1 Writing CSV

```python
import csv

rows = [
    ["Name", "Age", "City"],
    ["Alice", 25, "Delhi"],
    ["Bob", 30, "Mumbai"],
]

with open(
    "people.csv",
    "w",
    newline="",
    encoding="utf-8"
) as file:
    writer = csv.writer(file)
    writer.writerows(rows)
```

`newline=""` is the recommended pattern when using the `csv` module so Python doesn't introduce unwanted newline translation.

---

## 15.2 Reading CSV

```python
import csv

with open("people.csv", "r", newline="", encoding="utf-8") as file:
    reader = csv.reader(file)

    for row in reader:
        print(row)
```

Output might look like:

```text
['Name', 'Age', 'City']
['Alice', '25', 'Delhi']
['Bob', '30', 'Mumbai']
```

---

## 15.3 Dictionary-based CSV

This is often easier for structured data.

```python
import csv

data = [
    {"name": "Alice", "age": 25, "city": "Delhi"},
    {"name": "Bob", "age": 30, "city": "Mumbai"},
]

with open(
    "people.csv",
    "w",
    newline="",
    encoding="utf-8"
) as file:
    fieldnames = ["name", "age", "city"]

    writer = csv.DictWriter(file, fieldnames=fieldnames)
    writer.writeheader()
    writer.writerows(data)
```

Reading:

```python
with open(
    "people.csv",
    "r",
    newline="",
    encoding="utf-8"
) as file:
    reader = csv.DictReader(file)

    for row in reader:
        print(row["name"], row["city"])
```

---

# 16. Working with JSON

JSON is ideal for configuration and structured application data.

Python provides the built-in `json` module.

## 16.1 Writing JSON

```python
import json

data = {
    "name": "Jarvis",
    "version": 1,
    "enabled": True,
    "skills": ["voice", "search", "tasks"]
}

with open("config.json", "w", encoding="utf-8") as file:
    json.dump(data, file, indent=4)
```

This produces readable JSON.

---

## 16.2 Reading JSON

```python
import json

with open("config.json", "r", encoding="utf-8") as file:
    data = json.load(file)

print(data["name"])
```

---

## 16.3 JSON error handling

Malformed JSON raises `json.JSONDecodeError`.

```python
import json

try:
    with open("config.json", encoding="utf-8") as file:
        config = json.load(file)

except FileNotFoundError:
    print("Configuration file missing.")

except json.JSONDecodeError as exc:
    print(f"Invalid JSON: {exc}")

except OSError as exc:
    print(f"File error: {exc}")
```

---

# 17. Reading and Writing Strings Directly with `pathlib`

For small text files, `pathlib` has convenient methods.

Write:

```python
from pathlib import Path

path = Path("message.txt")

path.write_text(
    "Hello from Python!",
    encoding="utf-8"
)
```

Read:

```python
content = path.read_text(encoding="utf-8")

print(content)
```

This is convenient for small files.

For more complex streaming or binary operations, use `open()`.

---

# 18. Binary File Copying

You don't need to manually read and write binary files for ordinary copying.

Instead of:

```python
with open("source.jpg", "rb") as src:
    data = src.read()

with open("copy.jpg", "wb") as dst:
    dst.write(data)
```

prefer:

```python
import shutil

shutil.copy2("source.jpg", "copy.jpg")
```

Why?

Because `shutil` handles the operation more appropriately and your code is simpler.

---

# 19. A Production-Quality File Reader

Instead of repeating fragile logic everywhere, encapsulate file handling.

```python
from pathlib import Path


def read_text_file(path: str | Path) -> str:
    file_path = Path(path)

    try:
        return file_path.read_text(encoding="utf-8")

    except FileNotFoundError as exc:
        raise FileNotFoundError(
            f"File not found: {file_path}"
        ) from exc

    except PermissionError as exc:
        raise PermissionError(
            f"Permission denied: {file_path}"
        ) from exc

    except OSError as exc:
        raise OSError(
            f"Unable to read file {file_path}: {exc}"
        ) from exc
```

Usage:

```python
try:
    content = read_text_file("config.json")
    print(content)

except FileNotFoundError:
    print("Create the configuration file first.")
```

This gives you a consistent error boundary.

---

# 20. Production-Quality JSON Loader

For an application such as a backend or assistant:

```python
from pathlib import Path
import json
from typing import Any


def load_json(path: str | Path) -> dict[str, Any]:
    file_path = Path(path)

    try:
        with file_path.open(
            "r",
            encoding="utf-8"
        ) as file:
            data = json.load(file)

    except FileNotFoundError as exc:
        raise FileNotFoundError(
            f"JSON file not found: {file_path}"
        ) from exc

    except PermissionError as exc:
        raise PermissionError(
            f"Cannot read JSON file: {file_path}"
        ) from exc

    except json.JSONDecodeError as exc:
        raise ValueError(
            f"Invalid JSON in {file_path}: "
            f"line {exc.lineno}, column {exc.colno}"
        ) from exc

    if not isinstance(data, dict):
        raise ValueError(
            f"Expected JSON object in {file_path}"
        )

    return data
```

This is much safer than:

```python
data = json.load(open("config.json"))
```

---

# 21. Production Best Practices

### Use context managers

Prefer:

```python
with open(...) as file:
    ...
```

over manually closing files.

### Specify encoding

Prefer:

```python
encoding="utf-8"
```

for text files unless there's a specific reason to use another encoding.

### Use `pathlib`

Prefer:

```python
Path("data") / "config.json"
```

over manually assembling paths.

### Stream large files

Don't do this for a 10 GB log:

```python
content = file.read()
```

Prefer:

```python
for line in file:
    process(line)
```

### Catch specific exceptions

Prefer:

```python
except FileNotFoundError:
```

over:

```python
except Exception:
```

### Validate external data

Files can be malformed or malicious.

For JSON:

```python
data = json.load(file)
```

does not mean the data has the structure your application expects.

Validate required fields:

```python
if "name" not in data:
    raise ValueError("Missing required field: name")
```

For larger systems, use a schema/validation layer.

### Avoid hard-coded paths

Bad:

```python
"/home/user/project/config.json"
```

Better:

```python
BASE_DIR = Path(__file__).resolve().parent
config_path = BASE_DIR / "config.json"
```

### Consider atomic writes

For important configuration or state files, writing directly can leave a corrupted/truncated file if the process crashes during the write.

A safer pattern is:

```text
Write temporary file
       ↓
Flush / close
       ↓
Replace original
```

For example:

```python
from pathlib import Path
import os
import tempfile


def atomic_write(path: str | Path, content: str) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)

    fd, temp_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        text=True
    )

    try:
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            file.write(content)
            file.flush()
            os.fsync(file.fileno())

        os.replace(temp_name, path)

    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise
```

`os.replace()` is particularly useful for replacing a destination atomically on supported platforms.

---

# 22. Common Beginner Mistakes

### Mistake 1: Forgetting `with`

Bad:

```python
file = open("data.txt")
data = file.read()
```

The file might remain open.

Better:

```python
with open("data.txt", encoding="utf-8") as file:
    data = file.read()
```

---

### Mistake 2: Accidentally destroying a file with `w`

```python
open("important.txt", "w")
```

This truncates the file.

Use append:

```python
open("important.txt", "a")
```

when you intend to preserve existing content.

---

### Mistake 3: Using `read()` on huge files

Bad:

```python
data = file.read()
```

for massive logs or datasets.

Better:

```python
for line in file:
    process(line)
```

---

### Mistake 4: Assuming `readlines()` is cheap

```python
lines = file.readlines()
```

loads all lines into memory.

For large files, iterate directly.

---

### Mistake 5: Forgetting encoding

```python
open("file.txt")
```

may depend on the operating system's default encoding.

Better:

```python
open("file.txt", encoding="utf-8")
```

---

### Mistake 6: Treating binary files as text

This is wrong for arbitrary binary data:

```python
open("image.png", "r")
```

Use:

```python
open("image.png", "rb")
```

---

### Mistake 7: Manually constructing CSV

Don't assume:

```python
f"{name},{age}\n"
```

is always valid CSV.

Use:

```python
csv.writer(...)
```

---

### Mistake 8: Building paths with string concatenation

Bad:

```python
path = folder + "/" + filename
```

Better:

```python
path = Path(folder) / filename
```

---

### Mistake 9: Swallowing exceptions

Bad:

```python
try:
    ...
except:
    pass
```

This makes debugging miserable.

---

### Mistake 10: Checking existence everywhere before every operation

This pattern:

```python
if path.exists():
    path.unlink()
```

is not inherently wrong, but it can be vulnerable to race conditions in concurrent environments.

Often:

```python
try:
    path.unlink()
except FileNotFoundError:
    pass
```

is more appropriate.

---

# 23. Practical Example: File Manager Utility

Here's a compact utility combining the principles above:

```python
from pathlib import Path
import shutil
from typing import Iterable


class FileManager:
    def __init__(self, base_dir: str | Path):
        self.base_dir = Path(base_dir)

    def path(self, name: str) -> Path:
        return self.base_dir / name

    def read_text(self, name: str) -> str:
        path = self.path(name)

        try:
            return path.read_text(encoding="utf-8")
        except OSError as exc:
            raise OSError(
                f"Unable to read {path}"
            ) from exc

    def write_text(
        self,
        name: str,
        content: str,
    ) -> None:
        path = self.path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def append_text(
        self,
        name: str,
        content: str,
    ) -> None:
        path = self.path(name)
        path.parent.mkdir(parents=True, exist_ok=True)

        with path.open(
            "a",
            encoding="utf-8",
        ) as file:
            file.write(content)

    def copy(
        self,
        source: str,
        destination: str,
    ) -> None:
        src = self.path(source)
        dst = self.path(destination)

        dst.parent.mkdir(parents=True, exist_ok=True)

        shutil.copy2(src, dst)

    def move(
        self,
        source: str,
        destination: str,
    ) -> None:
        src = self.path(source)
        dst = self.path(destination)

        dst.parent.mkdir(parents=True, exist_ok=True)

        shutil.move(src, dst)

    def delete(self, name: str) -> None:
        path = self.path(name)

        try:
            path.unlink()
        except FileNotFoundError:
            pass

    def exists(self, name: str) -> bool:
        return self.path(name).exists()

    def size(self, name: str) -> int:
        return self.path(name).stat().st_size
```

Usage:

```python
manager = FileManager("./data")

manager.write_text(
    "hello.txt",
    "Hello Python\n"
)

manager.append_text(
    "hello.txt",
    "Second line\n"
)

print(manager.read_text("hello.txt"))

print("Exists:", manager.exists("hello.txt"))
print("Size:", manager.size("hello.txt"))
```

---

# 24. The Mental Model to Remember

Think of file handling as five layers:

```text
Path
 ↓
Open
 ↓
Read / Write
 ↓
Validate
 ↓
Close / Handle Errors
```

For most ordinary text files:

```python
from pathlib import Path

path = Path("data.txt")

try:
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            process(line)

except FileNotFoundError:
    ...
except PermissionError:
    ...
except OSError:
    ...
```

For structured data:

```text
CSV  → csv
JSON → json
Binary → rb / wb
Large text → iteration
File management → pathlib + shutil
```

The production mindset is simple: **don't treat file I/O as guaranteed. Files can disappear, permissions can change, disks can fill, encodings can differ, data can be malformed, and processes can crash mid-write.** Good Python code makes those failure modes explicit rather than hiding them.
