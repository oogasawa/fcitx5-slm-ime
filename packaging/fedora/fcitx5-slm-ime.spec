# The CMake project is in engine/
%global _vpath_srcdir engine

Name:           fcitx5-slm-ime
Version:        2.1.0
Release:        1%{?dist}
Summary:        Japanese input method for fcitx5 based on a small Transformer model

License:        Apache-2.0
URL:            https://github.com/oogasawa/fcitx5-slm-ime
Source0:        %{url}/archive/v%{version}/%{name}-%{version}.tar.gz
# The trained model (slm-ime-weights.bin and tokenizer.json), from the release of the same version
Source1:        %{url}/releases/download/v%{version}/slm-ime-model-%{version}.tar.gz

BuildRequires:  cmake
BuildRequires:  gcc-c++
BuildRequires:  gettext
BuildRequires:  pkgconfig(Fcitx5Core)
BuildRequires:  fcitx5-devel
BuildRequires:  eigen3-devel
BuildRequires:  json-devel
BuildRequires:  anthy-unicode-devel
Requires:       fcitx5
Requires:       slm-ime-model = %{version}-%{release}
Recommends:     xdg-utils

%description
A Japanese kana-to-kanji input method editor (IME) powered by a small language
model (SLM): a character-level Transformer (encoder-decoder) of about 60
million parameters that converts hiragana into kanji-kana text. The model is
small enough to run on the CPU of an ordinary laptop or mini PC alone; no GPU
is needed. It runs on Linux, on top of fcitx5.

Conventional Japanese input methods first cut the typed hiragana into words
with a morphological analyzer, and choose the kanji using that cut. However, a
correct cut requires understanding the meaning of the whole sentence, and
understanding the meaning of the whole sentence requires a correct cut: there
is a circular dependency.

This program avoids this problem by using a small language model directly: the
small language model reads the typed hiragana and writes the kanji-kana text
together with the phrasal unit (bunsetsu) boundaries in one pass, with no
separate morphological analyzer.

%package -n slm-ime-model
Summary:        Trained model of fcitx5-slm-ime
License:        CC-BY-SA-4.0
BuildArch:      noarch

%description -n slm-ime-model
Weights and character vocabulary of the conversion model of fcitx5-slm-ime: a
character-level Transformer encoder-decoder with about 60 million parameters.
It was trained on the original sentences of the Japanese Wikipedia and of
FineWeb-2 (Japanese web pages); their readings were written by Gemma 4, and the
obvious errors were corrected by post-processing.

%prep
%autosetup -n %{name}-%{version}
mkdir -p model-release
tar -xf %{SOURCE1} -C model-release

%build
%cmake
%cmake_build

%install
%cmake_install
install -D -m 644 model-release/slm-ime-weights.bin %{buildroot}%{_datadir}/slm-ime/model/slm-ime-weights.bin
install -D -m 644 model-release/tokenizer.json %{buildroot}%{_datadir}/slm-ime/model/tokenizer.json
%find_lang %{name}

%check
%ctest

%files -f %{name}.lang
%license LICENSE
%doc README.md
%{_bindir}/slm-ime-keys
%{_libdir}/fcitx5/llm-ime.so
%{_datadir}/fcitx5/addon/llm-ime.conf
%{_datadir}/fcitx5/inputmethod/llm-ime.conf

%files -n slm-ime-model
%license LICENSE-model.txt
%doc MODEL_CARD.md
%dir %{_datadir}/slm-ime
%{_datadir}/slm-ime/model/

%changelog
* Tue Nov 03 2026 Osamu Ogasawara <osamu.ogasawara@gmail.com> - 2.1.0-1
- The candidate list opens once the model's candidates are in it and keeps its order
- Page_Down and Page_Up turn the pages; number keys choose on the page shown
- F5 shows the candidates of one kanji
- Dictionary candidates that keep a cut-off small kana are no longer offered
- Digits and punctuation typed twice stay as they are in the reading

* Mon Nov 02 2026 Osamu Ogasawara <osamu.ogasawara@gmail.com> - 2.0.1-1
- A letter typed during a conversion commits it and starts the next reading

* Sat Oct 31 2026 Osamu Ogasawara <osamu.ogasawara@gmail.com> - 2.0.0-1
- First package for Fedora
