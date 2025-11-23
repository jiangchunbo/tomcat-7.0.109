#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# -----------------------------------------------------------------------------
# Start Script for the CATALINA Server
# -----------------------------------------------------------------------------

# Better OS/400 detection: see Bugzilla 31132
os400=false
case "`uname`" in
OS400*) os400=true;;
esac

# resolve links - $0 may be a softlink
# 解析 link - $0 可能是一个软连接

# 假设脚本名一开始放在 $PRG
PRG="$0"

# -h 用来判断文件是否为符号链接 soft link/symbolic link
# -d 只列出目录，而不会列出目录里面的文件等
while [ -h "$PRG" ] ; do
  ls=`ls -ld "$PRG"`

  # 使用正则抽取 -> 右边真正指向的路径
  link=`expr "$ls" : '.*-> \(.*\)$'`

  # 如果 link 是绝对路径 [/ 开头]
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  # 否则就要根据当前目录，拼接上相对路径
  else
    PRG=`dirname "$PRG"`/"$link"
  fi

  # 循环直到不再是符号链接
done

# 获取目录 -> 下面从这个目录里面执行 catalina.sh
PRGDIR=`dirname "$PRG"`
EXECUTABLE=catalina.sh

# Check that target executable exists
if $os400; then
  # -x will Only work on the os400 if the files are:
  # 1. owned by the user
  # 2. owned by the PRIMARY group of the user
  # this will not work if the user belongs in secondary groups
  eval
else
  if [ ! -x "$PRGDIR"/"$EXECUTABLE" ]; then
    echo "Cannot find $PRGDIR/$EXECUTABLE"
    echo "The file is absent or does not have execute permission"
    echo "This file is needed to run this program"
    exit 1
  fi
fi

exec "$PRGDIR"/"$EXECUTABLE" start "$@"
