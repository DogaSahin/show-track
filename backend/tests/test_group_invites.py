import pytest

from app.groups.invites import ALPHABET, CODE_LENGTH, generate_code, normalise_code


def test_a_generated_code_is_the_specified_length_and_alphabet():
    code = generate_code()

    assert len(code) == CODE_LENGTH
    assert set(code) <= set(ALPHABET)


def test_the_alphabet_excludes_every_confusable_letter():
    """Crockford base32. The exclusion is what makes folding O->0 and I/L->1 unambiguous:
    a typed O can only ever have meant 0, because the generator cannot emit O."""
    assert set("ILOU").isdisjoint(ALPHABET)
    assert len(set(ALPHABET)) == 32


def test_generated_codes_differ():
    """Weak by design — it catches a constant or seeded generator, which is the failure that
    would silently make every group share one code."""
    assert len({generate_code() for _ in range(200)}) == 200


@pytest.mark.parametrize(
    "typed",
    ["abcdefgh2345", "ABCD-EFGH-2345", " ABCDEFGH2345 ", "abcd efgh 2345", "ABCD_EFGH_2345"],
)
def test_normalisation_accepts_how_a_human_retypes_a_code(typed):
    assert normalise_code(typed) == "ABCDEFGH2345"


def test_normalisation_folds_confusable_letters_onto_their_digits():
    assert normalise_code("OIL") == "011"


def test_normalising_a_generated_code_is_a_no_op():
    """The property that matters: the write path stores what the read path will look for.
    If this ever fails, every code in the database is unreachable and the symptom looks
    like "the user typed it wrong"."""
    code = generate_code()

    assert normalise_code(code) == code
