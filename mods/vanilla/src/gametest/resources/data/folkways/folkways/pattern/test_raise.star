accepts = ["zone"]
capture = 0
knobs = [
    count("rounds", 0, 8192, default = 80),
    count("stuck", -1, 8192, default = -1),
    flag("last", default = False),
]

def grow(site):
    n = site.kept.get("n", 0)
    if n != site.round:
        fail("round and kept disagree")
    if n > 0 and not site.matches(point((n - 1) % 10, 0, (n - 1) // 10), "minecraft:stone"):
        fail("the previous round is missing")
    if n == site.count("stuck"):
        fail("test obstruction")
    if n >= site.count("rounds"):
        return []
    return grown([part("step", cells([(n % 10, 0, n // 10)]), ["minecraft:stone"])], keep = {"n": n + 1},
        last = site.flag("last") and n + 1 == site.count("rounds"))
