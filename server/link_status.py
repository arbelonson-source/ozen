import errno
import html
import os

PROBLEM_FILE = "encrypted-link-problem.txt"
PAGE_FILE = "pairing.html"
START = "<!--encrypted-link-problem-->"
END = "<!--/encrypted-link-problem-->"


def reason_for(error, port):
    if isinstance(error, ImportError):
        return (f"the 'cryptography' library is missing, so port {port} can't be encrypted. Run setup again (bash setup.sh on Linux, "
                "Ozen-Home-Setup.cmd on Windows) to install it")
    if isinstance(error, OSError) and error.errno in (errno.EADDRINUSE, 10048):
        return f"another program is already using port {port}. Close that program, then start Ozen again"
    return f"{error}. Restart Ozen; if it happens again, check the program's log"


def sentence(reason):
    return (f"The encrypted connection couldn't start: {reason}. "
            "Phones paired to it can't connect until this is fixed.")


def notice_block(folder):
    try:
        with open(os.path.join(folder, PROBLEM_FILE), encoding="utf-8") as f:
            text = f.read().strip()
    except OSError:
        return ""
    if not text:
        return ""
    return f'{START}<p role="alert" style="color:#b00020"><strong>{html.escape(text)}</strong></p>{END}\n'


def without_block(page):
    start = page.find(START)
    end = page.find(END)
    if start < 0 or end < start:
        return page
    tail = page[end + len(END):]
    return page[:start] + (tail[1:] if tail.startswith("\n") else tail)


def put_block(page, block):
    page = without_block(page)
    if not block:
        return page
    heading = page.find("</h1>")
    if heading < 0:
        return block + page
    cut = heading + len("</h1>")
    return page[:cut] + "\n" + block + page[cut:].lstrip("\n")


def record(folder, reason, port):
    text = sentence(reason_for(reason, port)) if isinstance(reason, BaseException) else sentence(reason)
    try:
        with open(os.path.join(folder, PROBLEM_FILE), "w", encoding="utf-8") as f:
            f.write(text + "\n")
        refresh_page(folder)
    except OSError:
        pass
    return text


def clear(folder):
    try:
        os.remove(os.path.join(folder, PROBLEM_FILE))
    except OSError:
        pass
    try:
        refresh_page(folder)
    except OSError:
        pass


def refresh_page(folder):
    path = os.path.join(folder, PAGE_FILE)
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        page = f.read()
    updated = put_block(page, notice_block(folder))
    if updated != page:
        mode = os.stat(path).st_mode & 0o777
        with open(path, "w", encoding="utf-8") as f:
            f.write(updated)
        os.chmod(path, mode)
