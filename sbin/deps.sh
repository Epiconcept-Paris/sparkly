#!/bin/bash

#Adding expected location for sparkly jar
sparkly_version=$(cat build.sbt | grep version)
sparkly_version=$(sed -r 's/(version|:=| |,|")//g' <<< $sparkly_version)
echo "com/github/epiconcept-paris/sparkly_2.12/$sparkly_version/sparkly_2.12-$sparkly_version.jar" > maven-paths.url

maven=https://repo1.maven.org/maven2/
echo "cheking dependencies\n"
for f in $(find lib_managed -type f);  do 
  IFS='/' read -ra parts <<< "$f"
  version1=$(sed 's/\.[^\.]*$//' <<< ${parts[4]:${#parts[3]}+1:${#parts[4]}}) 
  version2=$(sed 's/-[^\-]*$//' <<< ${parts[4]:${#parts[3]}+1:${#parts[4]}}) 
  url1=${parts[2]//\./\/}/${parts[3]}/$version1/${parts[4]} 
  url2=${parts[2]//\./\/}/${parts[3]}/$version2/${parts[4]} 
  if curl --output /dev/null --silent --head --fail "$maven$url1"; then 
    echo -e "\r\033[1A\033[0K OK $url1";
    echo $url1 >> maven-paths.url
  elif curl --output /dev/null --silent --head --fail "$maven$url2"; then 
    echo -e "\r\033[1A\033[0K OK $url2"; 
    echo $url2 >> maven-paths.url 
  else   
    echo -e "\n\033[1A\033[0K Could not find $f in $maven, try adding the right url manually on maven-paths.url"; 
  fi 
done
echo -e "\n\033[1A\033[0K downloadable classpath written on maven-paths.url"; 

