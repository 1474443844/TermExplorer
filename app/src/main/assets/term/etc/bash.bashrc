# TermExplorer system bashrc
# Installed to: $PREFIX/etc/bash.bashrc  (PREFIX = <filesDir>/term)
# Loaded via: bash --rcfile "$PREFIX/etc/bash.bashrc" -i

# Prompt: green basename of cwd + " $ "
# export PS1='\[\e[32m\]\W\[\e[0m\] \$ '

# Colorful defaults when coreutils/toybox support them
alias ls='ls --color=auto' 2>/dev/null
alias ll='ls -l --color=auto' 2>/dev/null
alias grep='grep --color=auto' 2>/dev/null
alias egrep='egrep --color=auto' 2>/dev/null
alias fgrep='fgrep --color=auto' 2>/dev/null

shopt -s histappend
shopt -s histverify
export HISTCONTROL=ignoreboth
PS1='\[\e[32m\]\W\[\e[0m\] \$ '
